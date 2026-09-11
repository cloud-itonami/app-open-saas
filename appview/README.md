# open-saas appview

`open-saas-console-os4a5s1` は、OSS SaaS の設計を可視化するための appview です。

- `/`: ランディング兼コントロールプレーン UI（静的アセットへの委譲）
- `/healthz`: ヘルスチェック
- `GET /api/open-saas/blueprint`: 設計ブループリント
- `GET /api/open-saas/overview`: tenant / MRR / seat の集計
- `GET /api/open-saas/plans`: プラン一覧
- `GET /api/open-saas/tenants`: tenant 一覧（snapshot）
- `GET /api/open-saas/tenants/:tenantId`: tenant 1 件
- `POST /api/open-saas/tenants`: tenant 作成（workspace + owner + trial subscription を同時に作る）
- `POST /api/open-saas/tenants/:tenantId/workspaces`: workspace 追加
- `POST /api/open-saas/tenants/:tenantId/memberships`: membership 追加
- `GET /api/open-saas/subscriptions`: subscription 一覧
- `POST /api/open-saas/subscriptions/:subscriptionId/transition`: subscription 状態遷移
- `GET /api/open-saas/usage`: usage 一覧
- `POST /api/open-saas/usage`: usage 記録
- `GET /api/open-saas/audit`: 監査イベント

ローカルでの確認手順は `../docs/operator-quickstart.md`。`wrangler dev` は
この repo 単体では踏めない（`hono` 依存と `wrangler.jsonc` が切り出し元に残っている）。

## salesforce-crm-sfcrm9x3

Salesforce 相当の OSS CRM appview (M2.5)。

- Lexicons: `00-contracts/lexicons/com/etzhayyim/apps/opensaas/salesforce/` **（切り出し元 `etzhayyim/root` 側。この repo には無い）** (account, contact, lead, opportunity, case, activity, createLead, convertLead, listPipeline)
- Route: `https://salesforce-opensaas.etzhayyim.com/`（`kotodama.jsonld` の `routes[].host` が正）
- Tenancy: `did:web:<slug>.opensaas.etzhayyim.com` per tenant, seat DID = `did:web:<slug>.opensaas.etzhayyim.com:seat:<role>-<nn>`
- PII split (ADR-0018): emailHash / phoneHash を Tier 1 AT Record、raw PII は Tier 3 Preferences
- Write-Only Derived (η=100%): opportunity.stage / case.status / lead→converted の変化で `activity` を `kotodama.jsonld` derive rule が自動生成

API:

- `GET /api/salesforce/overview`
- `GET /api/salesforce/pipeline?tenantDid=...`
- `GET /api/salesforce/{accounts,contacts,leads,cases,activities}`
- `POST /api/salesforce/leads` — createLead (emailHash は `sha256:<hex>` 必須、raw PII は拒否)
- `POST /api/salesforce/leads/convert` — convertLead (Account+Contact+Opportunity 原子書き込み)
- `POST /api/salesforce/opportunities/:uri/stage` — stage 遷移 (activity 自動派生)

cleanroom Salesforce REST（`salesforce_py_kotodama.py`、`runtimeType: worker-py`）:

- `GET|POST|PATCH|DELETE /services/data/v58.0/sobjects/:sobject_name` …
- `GET /services/data/v58.0/query/` / `POST /services/data/v58.0/composite/`

wire は Salesforce 互換のまま（クライアントは `Email` を送ってよい）だが、**基板に
落ちるのは `sha256:` 前置のハッシュだけ**。書ける属性は `ATTR_ALLOWLIST` に限られ、
それは `salesforce-schema.kotoba.edn` の部分集合でなければならない。

ローカル: `../docs/operator-quickstart.md` §2（ドメインテスト）。

## 宣言されているが、この repo に実装が無い経路

`kotodama.jsonld` の `triggers.http.routes` は、この repo に実装が無い prefix も
宣言している。**宣言だけがあって応答するものが無い**ので、ここに明記する ——
`test/open_saas/contracts_test.cljk` がこの一覧と manifest を両方向に突き合わせる
（実装が無いのに載っていなければ赤、実装されたのに載ったままでも赤）。

- `/at/...` — AT/W Protocol の XRPC 面（createLead / convertLead / listPipeline）。
  Lexicon は切り出し元 `etzhayyim/root` 側に残っており、この repo には実装が無い。
