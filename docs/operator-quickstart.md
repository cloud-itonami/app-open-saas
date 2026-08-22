# operator quickstart — app-open-saas

この手順は **2026-08-22、commit `e97d4fb`、Node v26.3.0 / npm 11** で実際に踏んだもの。
書いてある出力はそのときの実測。踏めない手順（`wrangler dev`）は載せていない —
理由は README の「動かないもの」。

## 0. 前提

- Node **≥ 22.18**（§3 の型ストリップ実行に要る。vitest だけなら古くても動く）
- npm。依存は各 appview の `package-lock.json` に固定（`typescript` / `vitest` のみ）

```sh
git clone git@github.com:cloud-itonami/app-open-saas.git
cd app-open-saas
```

## 1. オペレータコンソールのドメインテスト

```sh
cd appview/open-saas-console-os4a5s1
npm ci --no-audit --no-fund      # exit 0。fsevents の allow-scripts 警告は無視してよい
npm test                         # vitest run
```

実測の末尾:

```
 Test Files  1 passed (1)
      Tests  14 passed (14)
```

固定している不変条件（`test/open-saas-domain.test.ts` の describe 名から）:
`createTenant` が tenant + workspace + owner + trial subscription + audit event を
一度に作り、空の name / 不正 email / 未知 plan を拒否すること、self-serve tenant を
足すと `getOverview().totalMrrJpy` がその plan の価格分だけ増えること、
`churn-risk` の tenant は usage に関係なく `riskLevel=action` になること、
`transitionSubscription` が tenant を active に反転させ audit を残すこと、
`recordUsage` が非正の数量を拒否すること。

## 2. Salesforce 相当 CRM のドメインテスト

```sh
cd ../salesforce-crm-sfcrm9x3
npm ci --no-audit --no-fund
npm test
```

実測の末尾:

```
 Test Files  1 passed (1)
      Tests  14 passed (14)
```

固定している不変条件（`test/salesforce-domain.test.ts` の describe 名から）:
**lead は raw PII を拒否し `sha256:` 付き emailHash だけ受理する**、`convertLead` が
Account + Contact + Opportunity を作って lead を converted にし、既 converted /
未知の lead は拒否する、`advanceStage` が probability / forecastCategory を再割当して
stage-change activity を残す（closed-won → 100% / closed-lost → 0%）、
weighted pipeline = Σ round(amount × probability / 100) で open stage のみ。

## 3. ブループリントと overview を API 無しで見る

`src/app.ts` は動かないが、ドメインモジュールは純 TypeScript（erasable syntax のみ）
なので Node の型ストリップで直接 import できる。

```sh
cd ../open-saas-console-os4a5s1
node -e 'import("./src/open-saas-domain.ts").then(m => {
  console.log(Object.keys(m.getBlueprint()));
  console.log(JSON.stringify(m.getOverview()).slice(0, 200));
})'
```

実測:

```
[ 'name', 'posture', 'thesis', 'priorities', 'principles', 'apiDomains' ]
{"generatedAt":"…","totalTenants":3,"activeTenants":2,"totalMrrJpy":1578000,"totalAssignedSeats":8,…
```

同じ形で `salesforce-crm-sfcrm9x3` の `getOverview()` / `listPipeline({...})` も呼べる
（export 一覧は `grep -n '^export function' src/salesforce-domain.ts`）。
状態は in-memory のシードデータで、プロセスを抜けると消える。

## 4. 型検査は赤い（既知、期待どおり）

```sh
npx tsc --noEmit -p tsconfig.json     # exit 2
# src/app.ts(5,22): error TS2307: Cannot find module 'hono' ...
```

`hono` が依存に無いため。**ドメイン層には型エラーは無い**（エラーは全部 `src/app.ts`）。
Worker として動かしたい場合の未着手項目:

1. `hono` を `dependencies` に足す
2. `wrangler.jsonc`（`ASSETS` binding → `static-ui/`）を各 appview に置く
3. そのうえで `wrangler dev --config appview/<name>/wrangler.jsonc`

ここまでやって初めて `appview/README.md` の API 一覧が実際に叩ける。

## 5. 後片付け

```sh
git status --short     # node_modules/ は .gitignore 済みで出ない
```
