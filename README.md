# app-open-saas

**`cloud-itonami/app-open-saas`** は、OSS として監査可能な SaaS 基盤（マルチテナント・
課金/メータリング・監査ログ・Salesforce 相当 CRM）の**設計ブループリントと
決定論的ドメインロジック**を持つリポジトリです。旧名は
`etzhayyim-project-open-saas`（`etzhayyim/root` の `60-apps/etzhayyim-project-open-saas`
から 2026-08 に切り出し。出所は `migration.edn` が revision / tree / file 数まで記録）。
`PROJECT.jsonld` の `identifier` は `etzhayyim-open-saas` のまま。

## 構成

| パス | 何か |
|---|---|
| `PROJECT.jsonld` | プロジェクト定義（capabilities / survivalIndicators。値は全部 `null` = 未計測） |
| `scheduler.jsonld` | マイルストーン（M1 blueprint → M2 tenant billing → M2.5 Salesforce CRM → M3 …）と blocker |
| `appview/open-saas-console-os4a5s1/` | オペレータコンソール。`src/open-saas-domain.ts`（tenant / workspace / seat / subscription / usage / audit、in-memory）+ `src/app.ts`（Hono ルート）+ `static-ui/` |
| `appview/salesforce-crm-sfcrm9x3/` | Salesforce 相当 CRM。`src/salesforce-domain.ts`（lead / convert / opportunity stage / pipeline、PII は `sha256:` ハッシュのみ受理）+ `src/app.ts` + `sales/`（営業資料 13 本） |
| `migration.edn` / `README.edn` / `project.json` | 切り出しの出所と repository identity |

## いま動くもの・動かないもの（2026-08-22 実測、commit `e97d4fb`）

- **動く**: 2 つの appview の**ドメイン層とそのテスト**。`npm ci && npm test`
  （vitest）で 14 + 14 件が通る。ドメインモジュールは Node ≥ 22.18 の型ストリップで
  `node -e 'import("./src/open-saas-domain.ts")…'` と直接呼べる。
- **動かない**: `src/app.ts`（Hono）。`hono` が `package.json` に無く
  （monorepo 時代はルートで解決していた）、`wrangler.jsonc` もこの repo には無い。
  `tsc --noEmit` は `Cannot find module 'hono'` で exit 2 になる。**`wrangler dev` の
  手順はこの repo 単体では踏めない**ので、ここには書かない。
- **この repo に無いもの**: `appview/README.md` が参照する Lexicon
  （`00-contracts/lexicons/com/etzhayyim/apps/opensaas/salesforce/`）は切り出し元
  `etzhayyim/root` 側に残っている。

踏める手順は **`docs/operator-quickstart.md`**（実際に踏んだ出力つき）。

## 目的（切り出し前から不変）

- OSS として監査可能な SaaS 基盤を示す
- マルチテナント、課金、監査、拡張 API を最初から設計に入れる
- Cloudflare Worker ベースで小さく開始し、将来の分割に耐える

永続 DB 接続は未実装で、UI と API は設計検証用のサンプル実装。本番化では
tenant / subscription / usage meter / audit event を永続面に接続する前提
（`scheduler.jsonld` の M2 / M3）。
