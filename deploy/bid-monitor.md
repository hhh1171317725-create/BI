# Bid Monitor

Entry: `/tools` -> 出价监测 (`/bid-monitor.html`). No database migration or extra Nginx route is required. The existing static file handler serves the page; Spring handles `/api/bid-monitor/**`.

## Deploy

Run from a server terminal independent of the website:

```bash
cd /www/wwwroot/BI
git pull --ff-only origin main
export JAVA_HOME=/www/server/java/jdk-21.0.2
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw package && systemctl restart dahanghai-analysis
```

Only restart after a successful build. Administrators can enable `bidMonitor` for operators in the existing tool permission dialog. Both data endpoints enforce login and tool permission. Data and supplied upstream cookies are request-scoped, not shared between operators or persisted.

## Data And Interpretation

- Enter the customer's actual settlement price per media registration. It has no preset value.
- `convert_cnt / active_register` is the observed return ratio; it is not necessarily the configured return rate, especially during delayed attribution.
- The theoretical break-even bid is `price / ratio`. Target ceiling is `price / ratio * (1 - margin / 100)`.
- This assumes `cpa_bid` targets exactly the conversion counted in `convert_cnt`, and all registrations are billable at the entered price. Deep-conversion and dual-bid campaigns need a different analysis.
- Profit is estimated commission minus media spend, without rebates, refunds or other expenses.
- Both registrations and returned conversions must meet the adjustable minimum sample. This threshold is a heuristic, not a statistical confidence interval.
- Date ranges including today are marked provisional. Yesterday can still have delayed attribution; review after the project's attribution window closes.
- Results describe the dates captured when data was fetched/imported, not subsequently edited date controls.
- The list endpoint is paginated at 100 rows per call. Missing pages, duplicates with stable IDs, changing totals and the 200-page cap cause errors without replacing previous results. `getSum` is not mixed with list data; summaries are calculated from the same loaded rows.
- Excel import reads the first sheet with header row first. Required columns: `stat_cost`, `convert_cnt`, `active_register`, `cpa_bid`, or 消耗、转化数、注册数、出价. Use a plan-level period aggregate without totals or duplicate rows, and select its actual period before importing. Keep account/plan IDs formatted as text.

## Verification

`./mvnw package` passes all 109 Java tests. `node --test scripts/bid-monitor.test.cjs` verifies formulas and missing-data handling. `node scripts/bid-monitor-ui-test.cjs` uses isolated headless Chrome with mocked APIs, verifying desktop/mobile layout, import, search, pagination and export.

Live upstream probe on 2026-09-03 returned business code `-1` (illegal access). A successful live response has not yet been available for field/schema verification. No authentication controls are bypassed. Use a valid supported session or Excel import; provide a redacted successful response if the upstream schema differs.
