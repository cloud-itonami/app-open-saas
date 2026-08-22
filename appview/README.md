# open-saas appview

`open-saas-console-os4a5s1` は、OSS SaaS の設計を可視化するための appview です。

- `/`: ランディング兼コントロールプレーン UI
- `/api/open-saas/blueprint`: 設計ブループリント
- `/api/open-saas/demo-tenants`: デモ tenant 一覧
- `/healthz`: ヘルスチェック

ローカルでの確認手順は `../docs/operator-quickstart.md`。`wrangler dev` は
この repo 単体では踏めない（`hono` 依存と `wrangler.jsonc` が切り出し元に残っている）。

## salesforce-crm-sfcrm9x3

Salesforce 相当の OSS CRM appview (M2.5)。

- Lexicons: `00-contracts/lexicons/com/etzhayyim/apps/opensaas/salesforce/` **（切り出し元 `etzhayyim/root` 側。この repo には無い）** (account, contact, lead, opportunity, case, activity, createLead, convertLead, listPipeline)
- Route: `https://salesforce.opensaas.etzhayyim.com/`
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

ローカル: `../docs/operator-quickstart.md` §2（ドメインテスト）。
