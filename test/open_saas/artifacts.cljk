;; artifacts.cljs — この repo が持っている **実物** を読む層。
;;
;; ここが読むのは 3 種類ある:
;;
;;   1. 宣言（README.md / appview/README.md / docs/operator-quickstart.md /
;;      *.jsonld / *.kotoba.edn / *.py）—— テキストとして読む
;;   2. 実装（appview/*/src/*.ts）—— **Node の型ストリップで実際に import する**
;;   3. repo が宣言しているファイル一覧 —— git に訊く
;;
;; ## なぜ写しを置かないか
;;
;; 期待値をテスト側に焼くと、実物が動いたときテストは緑のまま古い値を守り続ける。
;; 検査したいのは実物なので、実物を読む。docs/operator-quickstart.md が印字して
;; いる数字も、README が数えている件数も、**それが書かれているファイルから読む**。
;;
;; ## 「測れなかった」を「問題なし」と区別する（superproject CLAUDE.md）
;;
;; この形の検査の既定の失敗は、**読み損ねたときに空集合になり、空集合との比較が
;; 常に真になる**ことである。ファイルが無い・正規表現が噛まない・import が転ける
;; —— どれも「違反 0 件」と同じ顔で出てくる。だから読めなかったら throw する。
;; 0 件を検査して 0 件の違反は、合格ではない。
(ns open-saas.artifacts
  (:require [kotoba.lang.text :as str]
            [clojure.set :as set]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def root (js/process.cwd))

(defn- abs [rel] (path/join root rel))

(defn slurp*
  "読めなければ throw する。`nil` を返すと、その先の検査が空文字を走査して
   『違反 0 件』になる。"
  [rel]
  (let [p (abs rel)]
    (when-not (fs/existsSync p)
      (throw (ex-info (str "無いファイルを読もうとした: " rel
                           "。読めていないので合格を報告しない。") {:rel rel})))
    (.toString (fs/readFileSync p "utf8"))))

(defn exists? [rel] (fs/existsSync (abs rel)))

(defn tracked
  "repo が宣言しているファイル。git に訊く —— fs の walk は node_modules を
   拾ってしまうし、『無い』と『見ていない』を区別できない。"
  []
  (let [out (try (.toString (cp/execFileSync "git" #js ["ls-files"]
                                             #js {:cwd root :maxBuffer 33554432}))
                 (catch :default e
                   (throw (ex-info (str "git ls-files が実行できないので、この repo が"
                                        " 何を宣言しているか分からない。合格を報告しない。 "
                                        (.-message e)) {:root root}))))
        fs* (vec (remove str/blank? (str/split-lines out)))]
    (when (empty? fs*)
      (throw (ex-info "git ls-files が 0 件を返した。0 件の検査は合格ではない。" {})))
    fs*))

(defn appview-dirs
  "`appview/<name>/kotodama.jsonld` を持つディレクトリ。**2 つ未満なら読めていない**
   —— この repo は console と salesforce-crm の 2 appview を持つ。"
  []
  (let [ds (->> (tracked)
                (keep #(second (re-matches #"appview/([^/]+)/kotodama\.jsonld" %)))
                sort vec)]
    (when (< (count ds) 2)
      (throw (ex-info (str "appview を " (count ds) " 個しか見つけられなかった（床 2）。"
                           " tree の読み方が壊れている。") {:found ds})))
    ds))

(defn read-json [rel] (js->clj (js/JSON.parse (slurp* rel)) :keywordize-keys true))

(defn read-edn-all
  "EDN を **最後まで** 消費して読む。`read-string` は最初の form だけ読んで残りを
   捨てるので、早く閉じた map の後ろにゴミが続くファイルが clean として通る
   （superproject CLAUDE.md が heredoc の `\\\"` について記録しているのと同じ形）。"
  [rel]
  (let [text (slurp* rel)
        forms (edn/read-string (str "[" text "]"))]
    (when-not (= 1 (count forms))
      (throw (ex-info (str rel " が EDN の form 1 個ではなく " (count forms) " 個だった。"
                           " 読み方が合っていないので合格を報告しない。") {:rel rel})))
    (first forms)))

;; ── 実装を実際に走らせる ────────────────────────────────────────────────────
;;
;; `src/*-domain.ts` は外部 import を持たない純 TypeScript（erasable syntax のみ）
;; なので、Node 26 の型ストリップでそのまま import できる。`hono` を要求するのは
;; `src/app.ts` の方で、ここでは触らない。

(defn import-domain
  "appview のドメインモジュールを実物として import する。promise を返す。"
  [appview module-basename]
  (let [rel (str "appview/" appview "/src/" module-basename ".ts")]
    (when-not (exists? rel)
      (throw (ex-info (str "ドメインモジュールが無い: " rel) {:rel rel})))
    (-> (js/import (str "file://" (abs rel)))
        (.catch (fn [e]
                  (throw (ex-info (str rel " を import できなかったので、この appview について"
                                       " 何も測れていない。合格を報告しない。 " (.-message e))
                                  {:rel rel})))))))

;; ── ルート宣言を読む ────────────────────────────────────────────────────────

(defn- ts-routes
  "`src/app.ts` が **明示的に** 登録している経路。

   `app.all(...)` は意図的に除く。console の `app.all(\"/api/*\")` は
   `{\"error\":\"notFound\"}` を 404 で返す **拒否** であって登録ではない ——
   これを登録として数えると、README が実在しない `/api/...` を案内していても
   『catch-all が受けるから登録済み』と読めてしまい、検査が何も判別しなくなる。"
  [appview]
  (let [rel (str "appview/" appview "/src/app.ts")]
    (when (exists? rel)
      (into #{} (map (fn [[_ verb p]] {:verb (str/upper verb) :path p})
                     (re-seq #"app\.(get|post|put|patch|delete)\(\s*\"([^\"]+)\"" (slurp* rel)))))))

(defn- py-routes
  "同じ appview の Python 実装（FastAPI）が登録している経路。

   **この repo の appview は 1 つが 2 実装を持つ。** salesforce-crm-sfcrm9x3 の
   `kotodama.jsonld` は `runtimeType: worker-py` を宣言しており、
   `/services/data/v58.0/...` を出しているのは `app.ts` ではなく py の方である。
   TypeScript だけを読むと、実装されている面を『未実装』と報告する ——
   実際 2026-09-01 にこの検査の最初の版がそう誤報した。

   FastAPI の `{x}` は Hono の `:x` に正規化して、経路の照合を 1 つの規則で行う。"
  [appview]
  (let [dir (str "appview/" appview)
        pys (filter #(and (str/starts-with? % (str dir "/")) (str/ends-with? % ".py"))
                    (tracked))]
    (into #{} (mapcat (fn [rel]
                        (map (fn [[_ verb p]]
                               {:verb (str/upper verb)
                                :path (str/replace p #"\{([^}]+)\}" ":$1")})
                             (re-seq #"@app\.(get|post|put|patch|delete)\(\s*\"([^\"]+)\"" (slurp* rel))))
                      pys))))

(defn registered-routes
  "その appview が実際に登録している経路。**実装言語をまたいで集める。**"
  [appview]
  (let [rs (set/union (or (ts-routes appview) #{}) (py-routes appview))]
    (when (empty? rs)
      (throw (ex-info (str appview " から経路を 1 本も読めなかった。"
                           " 走査が壊れているので合格を報告しない。") {:appview appview})))
    rs))

(defn has-static-fallback?
  "`app.all(\"*\")`（静的アセットへの委譲）を持つか。README が `/` を案内できる
   のはこれが在るときだけ。"
  [appview]
  (boolean (re-find #"app\.all\(\s*\"\*\"" (slurp* (str "appview/" appview "/src/app.ts")))))
