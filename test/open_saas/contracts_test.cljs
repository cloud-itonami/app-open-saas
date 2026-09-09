;; contracts_test.cljs — app-open-saas の文書と実装が **互いについて言っていること**
;; が、今も本当かどうか。
;;
;; この repo には既に vitest が 14 + 14 件ある。それらは各 appview の**ドメイン層を
;; 単体で**見ており、よく効いている。効いていないのは、この repo が実際に持って
;; いる別の種類の主張である —— **複数のファイルが同じことについて別々に語り、
;; どれが破れても何も throw しない**:
;;
;;   appview/README.md          ── API 一覧と serving host を案内する
;;   appview/*/src/app.ts       ── 実際に登録される経路
;;   appview/*/kotodama.jsonld  ── デプロイ宣言（host / route prefix / name）
;;   docs/operator-quickstart.md ── 実測として **数字を印字している**
;;   README.md                  ── テストの件数を数えている
;;   salesforce-schema.kotoba.edn / salesforce_py_kotodama.py
;;                              ── TS とは **別の実装**が同じ DID に書く
;;
;; 破れ方はこうなる:
;;
;;   * README が実在しない `/api/...` を案内する → 読んだ人は 404 に当たる。
;;     console は `app.all("/api/*")` で 404 JSON を返すので、**サーバは正常に
;;     応答し続ける**。誰も落ちない。
;;   * seed データが動く → quickstart が印字した `totalMrrJpy` は古くなるが、
;;     ファイルは実測として自信のある過去形で主張し続ける。
;;   * テストを 1 本足す → README の「14 + 14 件」が黙って嘘になる。
;;   * 基板スキーマが raw な email を宣言する → TS 側は `sha256:` を強制して
;;     いるのに、**同じ DID に書くもう一方の実装**が生の PII を受ける。
;;     この repo の看板の主張（ADR-0018 の PII split）が、片側だけで守られる。
;;
;; ## 期待値を焼かない
;;
;; 数字も経路も host も、**それが書かれているファイルから読む**。テスト側に写しを
;; 置くと、写しの方だけが正しいまま実物が腐る。読み損ねたら床で throw する
;; （open-saas.artifacts を参照）—— 空集合との比較は常に真であって、合格ではない。
(ns open-saas.contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [kotoba.lang.text :as str]
            [open-saas.artifacts :as a]
            ["child_process" :as cp]))

;; ── 走らせた実物を持ち回す ──────────────────────────────────────────────────
(defonce domains (atom {}))
(defn- console [] (or (:console @domains) (throw (ex-info "console domain 未 import" {}))))
(defn- sfcrm [] (or (:sfcrm @domains) (throw (ex-info "sfcrm domain 未 import" {}))))

;; ── quickstart が印字している実測を読む ──────────────────────────────────────

(defn quickstart [] (a/slurp* "docs/operator-quickstart.md"))

(defn printed-blueprint-keys
  "quickstart §3 の `[ 'name', 'posture', … ]` を読む。"
  []
  (let [m (re-find #"\[ '([^\]]+)' \]" (quickstart))
        ks (when m (->> (str/split (second m) #"',\s*'") (mapv str/trim)))]
    (when (< (count ks) 3)
      (throw (ex-info (str "quickstart から blueprint のキー一覧を読めなかった（"
                           (count ks) " 件、床 3）。約束を読めていないので合格を報告しない。")
                      {:found ks})))
    ks))

(defn printed-overview-numbers
  "quickstart §3 が印字している `getOverview()` の数値。`\"key\":123` を全部拾う。"
  []
  (let [block (or (second (re-find #"(?s)\{\"generatedAt\":(.*?)\n```" (quickstart))) "")
        ns* (into {} (map (fn [[_ k v]] [k (js/Number v)])
                          (re-seq #"\"([A-Za-z]+)\":(\d+)" block)))]
    (when (< (count ns*) 3)
      (throw (ex-info (str "quickstart から overview の数値を読めなかった（"
                           (count ns*) " 件、床 3）。") {:found ns*})))
    ns*))

(deftest the-blueprint-keys-the-quickstart-prints-are-the-keys-the-module-exposes
  (testing "§3 は getBlueprint() のキー一覧を実測として印字している。モジュールが
            キーを足す/改名すると、その行だけが静かに古くなる"
    (is (= (printed-blueprint-keys)
           (vec (js->clj (js/Object.keys ((.-getBlueprint (console)))))))
        "docs/operator-quickstart.md §3 が印字するキー一覧が getBlueprint() と違う")))

(deftest the-overview-numbers-the-quickstart-prints-are-the-numbers-the-module-computes
  (testing "§3 は totalTenants / totalMrrJpy などを実測として印字している。seed が
            動けば全部古くなるが、ファイルは過去形で主張し続ける"
    (let [printed (printed-overview-numbers)
          actual  (js->clj ((.-getOverview (console))) :keywordize-keys false)]
      (doseq [[k v] printed]
        (is (= v (get actual k))
            (str "quickstart が印字した " k "=" v " に対し、getOverview() は "
                 (get actual k)))))))

;; ── README が数えているテスト件数 ───────────────────────────────────────────

(deftest the-readme-counts-the-tests-each-appview-actually-has
  (testing "README は「14 + 14 件が通る」と数えている。テストを 1 本足すとこの行は
            黙って嘘になる —— 数字を検査するものが無ければ、測ったように見えて古くなる"
    (let [readme (a/slurp* "README.md")
          m (re-find #"(\d+)\s*\+\s*(\d+)\s*件が通る" readme)
          _ (when-not m
              (throw (ex-info "README.md から件数の主張（`N + N 件が通る`）を読めなかった。" {})))
          claimed (mapv js/Number (rest m))
          counted (mapv (fn [av]
                          (count (re-seq #"\n\s*it\(" (a/slurp* (str "appview/" av "/test/"
                                                                     (first (filter #(str/ends-with? % ".test.ts")
                                                                                    (map #(last (str/split % #"/"))
                                                                                         (filter (fn [f] (str/starts-with? f (str "appview/" av "/test/")))
                                                                                                 (a/tracked)))))))))) 
                        (a/appview-dirs))]
      (is (= (sort claimed) (sort counted))
          (str "README.md は " claimed " 件と数えているが、実際の it( は " counted " 件")))))

;; ── README の API 一覧 ↔ 実際に登録される経路 ───────────────────────────────

(defn documented-api-paths
  "appview/README.md が backtick で案内している `/api/...` の経路。
   `{a,b,c}` のブレース展開と `?query` を解く。"
  []
  (let [text (a/slurp* "appview/README.md")
        raw  (map second (re-seq #"`(?:GET |POST |PUT |PATCH |DELETE )?(/[^`\s]*)`" text))
        expand (fn [p]
                 (if-let [m (re-find #"^(.*)\{([^}]+)\}(.*)$" p)]
                   (let [[_ pre alts post] m]
                     (mapv #(str pre (str/trim %) post) (str/split alts #",")))
                   [p]))
        paths (->> raw (mapcat expand) (map #(first (str/split % #"\?"))) distinct vec)]
    (when (< (count paths) 5)
      (throw (ex-info (str "appview/README.md から案内されている経路を "
                           (count paths) " 本しか読めなかった（床 5）。走査が壊れている。")
                      {:found paths})))
    paths))

(defn- registered-path-set []
  (into #{} (mapcat (fn [av] (map :path (a/registered-routes av))) (a/appview-dirs))))

(defn- matches-registration? [documented registered]
  (some (fn [r]
          (let [rp (str/split r #"/")
                dp (str/split documented #"/")]
            (and (= (count rp) (count dp))
                 (every? true? (map (fn [rs ds] (or (str/starts-with? rs ":") (= rs ds)))
                                    rp dp)))))
        registered))

(deftest every-api-path-the-appview-readme-documents-is-registered-by-an-app
  (testing "README が案内する `/api/...` は、いずれかの app.ts が **明示的に**
            登録していなければならない。console の `app.all(\"/api/*\")` は 404 JSON を
            返す拒否なので登録として数えない —— 数えると、実在しない経路も
            『catch-all が受ける』ことになり検査が何も判別しなくなる"
    (let [registered (registered-path-set)
          api-docs   (filter #(str/starts-with? % "/api/") (documented-api-paths))]
      (is (seq api-docs) "README から /api/ の経路を 1 本も読めなかった")
      (doseq [p api-docs]
        (is (matches-registration? p registered)
            (str "appview/README.md が案内する " p " を登録している app.ts が無い"))))))

(deftest every-non-api-path-the-readme-documents-is-served-by-something
  (testing "`/` や `/healthz` は **appview ごとに** 応答できなければならない。両 manifest が
            `\"/\"` と `spa: true` を宣言しているので、どちらの appview も landing を出す。

            ⚠ 最初の版は『どれか 1 つの appview が catch-all を持てばよい』と書いており、
            console の `app.all(\"*\")` を潰しても sfcrm の分で緑のままだった（2026-09-01 実測）。
            経路を appview に帰属させない検査は、片方が壊れたことを判別できない"
    (let [others (remove #(str/starts-with? % "/api/") (documented-api-paths))]
      (is (seq others) "README から /api/ 以外の経路を 1 本も読めなかった")
      (doseq [av (a/appview-dirs)
              p  others]
        (is (or (matches-registration? p (map :path (a/registered-routes av)))
                (a/has-static-fallback? av))
            (str av " は README が案内する " p " に応答できない"
                 "（明示登録も静的 catch-all も無い）"))))))

;; ── kotodama.jsonld（デプロイ宣言）↔ 実装 ───────────────────────────────────

(deftest each-kotodama-manifest-names-the-directory-it-sits-in
  (testing "manifest の `name` が入っているディレクトリ名とずれると、デプロイ側と
            tree 側で別の appview を指すことになる"
    (doseq [av (a/appview-dirs)]
      (is (= av (:name (a/read-json (str "appview/" av "/kotodama.jsonld"))))
          (str "appview/" av "/kotodama.jsonld の name がディレクトリ名と違う")))))

(defn declared-prefixes
  "manifest の `triggers.http.routes` のうち `/x/...` 形の prefix。`/` と `/...` は
   catch-all なので除く。"
  [av]
  (let [decl (get-in (a/read-json (str "appview/" av "/kotodama.jsonld"))
                     [:triggers :http :routes])]
    (when (empty? decl)
      (throw (ex-info (str av " の manifest が http routes を宣言していない") {:av av})))
    [decl (->> decl
               (filter #(str/ends-with? % "/..."))
               (map #(subs % 0 (- (count %) 3)))
               (remove #(= % "/")))]))

(defn readme-unimplemented-prefixes
  "appview/README.md が「実装が無い」と明記している prefix。見出しごと消えたら
   **読めなかった**ので throw する —— 空集合を『明記が無い』と読むと、
   明記を消すだけで検査が緩む。"
  []
  (let [text (a/slurp* "appview/README.md")
        head "## 宣言されているが、この repo に実装が無い経路"]
    (when-not (str/includes? text head)
      (throw (ex-info (str "appview/README.md に見出しが無い: " head
                           "。宣言と実装のずれを何と突き合わせればよいか分からないので"
                           " 合格を報告しない。") {})))
    (->> (str/split (second (str/split text (re-pattern head))) #"\n## ")
         first str/split-lines
         (keep #(second (re-find #"^- `(/[^`]*)`" %)))
         (map #(str/replace % #"\.\.\.$" ""))
         set)))

(deftest every-http-route-prefix-the-kotodama-manifests-declare-has-a-registration
  (testing "manifest の `triggers.http.routes` は `/api/open-saas/...` のような prefix を
            宣言する。実装がその prefix に 1 本も無いなら、デプロイ宣言は誰も応答しない
            面を開けていることになる —— それが承知の上なら README に明記されていなければ
            ならない。**両方向に見る**: 明記が無いまま未実装なら赤、実装されたのに
            明記が残っていても赤（消し忘れた注記は嘘になる）"
    (let [noted (readme-unimplemented-prefixes)]
      (doseq [av (a/appview-dirs)]
        (let [[decl prefixes] (declared-prefixes av)
              registered (map :path (a/registered-routes av))]
          (is (seq decl) (str av " の manifest が http routes を宣言していない"))
          (doseq [pre prefixes]
            (let [served? (boolean (some #(str/starts-with? % pre) registered))]
              (if served?
                (is (not (contains? noted pre))
                    (str av " の " pre "... は実装されているのに、appview/README.md の"
                         " 「実装が無い経路」に載ったままである"))
                (is (contains? noted pre)
                    (str av " の manifest は " pre "... を宣言しているが、この appview の"
                         " どの実装（app.ts / *.py）にも登録が無く、"
                         " appview/README.md にも明記されていない"))))))))))

(deftest the-appview-readme-and-the-kotodama-manifest-name-the-same-host
  (testing "README が案内する serving host が manifest の routes[].host に無ければ、
            読んだ人は存在しないアドレスを叩く"
    (let [text  (a/slurp* "appview/README.md")
          ;; `<slug>` を含むものは per-tenant DID のテンプレートなので host ではない
          hosts (->> (re-seq #"https://([a-z0-9.-]+\.etzhayyim\.com)" text)
                     (map second) distinct
                     (remove #(str/includes? % "<")))
          declared (into #{} (mapcat (fn [av]
                                       (map :host (get (a/read-json (str "appview/" av "/kotodama.jsonld")) :routes)))
                                     (a/appview-dirs)))]
      (is (seq declared) "kotodama.jsonld から host を 1 つも読めなかった")
      (doseq [h hosts]
        (is (contains? declared h)
            (str "appview/README.md が案内する host " h " を宣言している "
                 "kotodama.jsonld が無い（宣言されているのは " (sort declared) "）"))))))

;; ── PII split（この repo の看板の主張）─────────────────────────────────────

(def raw-channel-attr
  "生で持ってはならない連絡先チャネル。`_hash` が付いていれば可。"
  #"(?i)(^|_)(email|phone|mobile|fax)$")

(defn schema-attrs
  "salesforce-schema.kotoba.edn が宣言する属性。`:<ns>/<attr>` を [ns attr] で返す。"
  []
  (let [forms (a/read-edn-all "appview/salesforce-crm-sfcrm9x3/salesforce-schema.kotoba.edn")
        idents (keep :db/ident forms)
        pairs (mapv (fn [k] [(namespace k) (name k)]) idents)]
    (when (< (count pairs) 10)
      (throw (ex-info (str "基板スキーマから属性を " (count pairs) " 個しか読めなかった（床 10）。")
                      {:found pairs})))
    pairs))

(deftest the-crm-substrate-schema-stores-no-raw-contact-channel
  (testing "README（ADR-0018）は emailHash / phoneHash だけを Tier 1 レコードにすると
            決めている。TS 側の createLead はそれを強制するが、**同じ DID に書く
            もう一方の実装**（py cleanroom + このスキーマ）が生の email を宣言すれば、
            看板の主張は片側だけで守られることになる"
    (doseq [[ns* attr] (schema-attrs)]
      (is (not (re-find raw-channel-attr attr))
          (str "salesforce-schema.kotoba.edn が :" ns* "/" attr " を宣言している。"
               " 連絡先チャネルはハッシュ（`sha256:` 前置の *_hash）でなければならない")))))

(deftest the-crm-domain-refuses-an-unhashed-email
  (testing "拒否そのものを実物に対して確かめる。**理由の literal まで見る** ——
            別の理由で throw したのを『拒否した』と数えないため"
    (let [thrown (try ((.-createLead (sfcrm))
                       #js {:tenantDid "did:web:t.example.com"
                            :emailHash "alice@example.com"
                            :source "web-form"})
                      nil
                      (catch :default e (.-message e)))]
      (is (some? thrown) "raw な email を渡したのに createLead が throw しなかった")
      (is (and thrown (str/includes? thrown "sha256:"))
          (str "throw はしたが理由が PII 拒否ではない: " thrown))
      (is (and thrown (str/includes? thrown "raw PII is rejected"))
          (str "throw はしたが理由が PII 拒否ではない: " thrown)))))

;; ── py cleanroom（TS とは別の実装）↔ 基板スキーマ ───────────────────────────

(defn py [] (a/slurp* "appview/salesforce-crm-sfcrm9x3/salesforce_py_kotodama.py"))

(defn py-sobject-namespaces
  "cleanroom API が受ける SObject → kotoba namespace の対応表。"
  []
  (let [block (or (second (re-find #"(?s)SOBJECT_MAPPING\s*=\s*\{(.*?)\}" (py))) "")
        m (into {} (map (fn [[_ k v]] [k v]) (re-seq #"\"([A-Za-z]+)\":\s*\"([a-z_]+)\"" block)))]
    (when (< (count m) 5)
      (throw (ex-info (str "py の SOBJECT_MAPPING を " (count m) " 件しか読めなかった（床 5）。")
                      {:found m})))
    m))

(defn py-allowed-attrs
  "cleanroom API が基板に書いてよい属性の allowlist。namespace → #{attr}。"
  []
  (let [block (or (second (re-find #"(?s)ATTR_ALLOWLIST\s*=\s*\{(.*?)\n\}" (py))) "")
        entries (re-seq #"\"([a-z_]+)\":\s*\{([^}]*)\}" block)]
    (into {} (map (fn [[_ ns* body]]
                    [ns* (into #{} (map second (re-seq #"\"([a-z_]+)\"" body)))])
                  entries))))

(deftest the-substrate-schema-declares-every-object-the-cleanroom-api-maps
  (testing "cleanroom API が受ける SObject には、基板スキーマ側に identity 属性が
            なければならない。無ければ upsert の軸が無いまま transact することになる"
    (let [attrs (schema-attrs)
          by-ns (group-by first attrs)
          idents (into #{} (map (fn [[ns* _]] ns*)
                                (filter (fn [[_ a]] (= a "id")) attrs)))]
      (doseq [[sobject ns*] (py-sobject-namespaces)]
        (is (contains? idents ns*)
            (str "cleanroom API は " sobject " を :" ns* "/… に写すが、"
                 "基板スキーマに :" ns* "/id が無い")))
      (doseq [[ns* _] by-ns]
        (is (contains? (into #{} (vals (py-sobject-namespaces))) ns*)
            (str "基板スキーマは :" ns* "/… を宣言しているが、"
                 "cleanroom API の SOBJECT_MAPPING に " ns* " が無い"))))))

(deftest the-cleanroom-api-writes-only-attributes-the-substrate-schema-declares
  (testing "py の書き込み経路は、クライアントが送った任意のキーではなく **宣言済みの
            属性だけ**を基板に書かなければならない。allowlist が無い（= 何でも書ける）
            とき、スキーマは基板の中身について何も言っていないことになる"
    (let [allow (py-allowed-attrs)
          declared (group-by first (schema-attrs))]
      (is (seq allow)
          "py に ATTR_ALLOWLIST が無い。cleanroom API はクライアントが送った任意の
           キーを基板に書けるので、基板スキーマは中身を拘束していない")
      (doseq [[ns* attrs] allow]
        (let [ok (into #{} (map second (get declared ns*)))]
          (is (seq ok) (str "py の allowlist は " ns* " を持つが、スキーマに :" ns* "/… が無い"))
          (doseq [attr attrs]
            (is (contains? ok attr)
                (str "py の allowlist は :" ns* "/" attr " を許すが、"
                     "基板スキーマがその属性を宣言していない"))))))))

(deftest the-runtime-the-manifest-declares-actually-parses
  (testing "salesforce-crm-sfcrm9x3 の `kotodama.jsonld` は `runtimeType: worker-py` を
            宣言し、`/services/data/v58.0/...` を出しているのはその py である。**その
            ファイルが Python として読めなければ、宣言された runtime は起動しない。**

            2026-09-01 実測: この検査を書いた時点で読めなかった —— EDN のキーワードが
            Python のリストに貼られており（`[[:db.fn/retractEntity, eid]]`）、
            module は import 段階で SyntaxError になっていた。誰も走らせていないので、
            宣言だけが 1 年ぶん正しく見えていた"
    (let [pys (filter #(and (str/starts-with? % "appview/") (str/ends-with? % ".py")) (a/tracked))]
      (is (seq pys) "py の実装を 1 つも見つけられなかった")
      (doseq [rel pys]
        (let [r (try (cp/spawnSync "python3"
                                   #js ["-c" "import ast,io,sys; ast.parse(io.open(sys.argv[1],encoding='utf-8').read())" rel]
                                   #js {:cwd a/root :encoding "utf8"})
                     (catch :default e
                       (throw (ex-info (str "python3 を起動できないので " rel
                                            " が読めるかどうか測れていない。合格を報告しない。 "
                                            (.-message e)) {:rel rel}))))]
          ;; 起動そのものが失敗した場合も測れていない —— 「落ちた」と区別する。
          (when (some? (.-error r))
            (throw (ex-info (str "python3 が起動しなかったので " rel " を測れていない。"
                                 " 合格を報告しない。") {:rel rel})))
          (is (zero? (.-status r))
              (str rel " が Python として読めない: " (str/trim (str (.-stderr r))))))))))

;; ── PROJECT.jsonld が指す先 ────────────────────────────────────────────────

(deftest the-project-file-points-at-documents-and-appviews-that-exist
  (testing "PROJECT.jsonld は scheduler ファイルと appview を名指しする。JSON としては
            正しいので、その宣言を辿ろうとするまで食い違いは出てこない"
    (let [proj (a/read-json "PROJECT.jsonld")
          sched (:scheduler proj)
          terms (get-in proj [:capabilities :terms])
          named (->> terms (map :description) (apply str)
                     (re-seq #"appview ([a-z0-9-]+)") (map second) distinct)]
      (is (string? sched) "PROJECT.jsonld に scheduler の指定が無い")
      (is (a/exists? sched) (str "PROJECT.jsonld が指す " sched " が repo に無い"))
      (is (seq named) "PROJECT.jsonld の capability が appview を 1 つも名指ししていない")
      (doseq [av named]
        (is (contains? (set (a/appview-dirs)) av)
            (str "PROJECT.jsonld は appview " av " を名指しするが、そのディレクトリが無い"))))))

;; ── 証拠を集められたか（runner が **テストより先に** 呼ぶ）──────────────────
;;
;; 上の読み取りはどれも床を持っていて、読めなければ throw する。しかしそれが
;; `deftest` の中で起きると cljs.test は error として拾い、**不変条件が破れたのと
;; 同じ exit 1** になる。「測れなかった」と「測って問題が見つかった」は別の事実
;; なので、読み取りだけを先に走らせて分離する（runner が catch して exit 2）。
(defn evidence!
  "全部の読み取りを 1 度ずつ走らせる。1 つでも読めなければ throw する。"
  []
  (printed-blueprint-keys)
  (printed-overview-numbers)
  (documented-api-paths)
  (readme-unimplemented-prefixes)
  (schema-attrs)
  (py-sobject-namespaces)
  (doseq [av (a/appview-dirs)] (declared-prefixes av) (a/registered-routes av))
  :ok)
