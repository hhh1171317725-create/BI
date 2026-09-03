# Bid Monitor

Entry: `/tools` -> 出价监测 (`/bid-monitor.html`). No extra Nginx route is required. The existing static file handler serves the page; Spring handles `/api/bid-monitor/**`. The snapshot endpoint automatically creates `bid_monitor_snapshots` on first use; the database user needs CREATE TABLE permission.

## Deploy

Run from a server terminal independent of the website:

```bash
cd /www/wwwroot/BI
git pull --ff-only origin main
export JAVA_HOME=/www/server/java/jdk-21.0.2
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw package && systemctl restart dahanghai-analysis
```

Only restart after a successful build. Administrators can enable `bidMonitor` for operators in the existing tool permission dialog. All endpoints enforce login and tool permission. Direct query cookies remain request-scoped. Browser synchronization saves only allowlisted plan metrics in one latest snapshot per website user, never cookies. Snapshot reads always use the current website user; uploads also verify the user bound when synchronization was enabled.

## Browser Scheduled Updates (Extension 1.9.4)

1. Download `/downloads/ad-tools-helper.zip`, extract and reload the unpacked extension at `chrome://extensions`. Accept the `alarms`, `storage`, `cookies`, `cl.mobgi.com` and `cli1.mobgi.com` permissions. Version 1.9.2 adds cookies/API host access. Refresh the BI page after updating.
2. In the same Chrome profile, log into Chuangliang and keep its page open. Log into BI and keep `/bid-monitor.html` open. Scheduled synchronization needs no copied Cookie. Click 读取当前创量用户 to fill `client-user`: the extension reads only the cookie named `userId`, scoped to the selected tab's Cookie store, first for the actual API URL and then for the page URL. This supports HttpOnly/API-host cookies without reading or returning authentication cookies. Page queries no longer depend on `document.cookie`; the worker verifies the user before each page and before uploading. Normal/incognito tabs are not mixed. The credentials area opens automatically; verify `main-user-id` for that same account. If neither URL has userId, synchronization still stops explicitly: provide only the client-user/main-user-id from a successful request to investigate another identity source. Detection itself does not enable synchronization.
3. Choose 5/10/15/30/60 minutes (default 10), click 启用并立即同步. A run starts immediately; later runs use Chrome alarms. Closing the popup does not stop scheduling. Closing Chrome, sleeping the computer, discarding/closing a required page or expiring the session prevents collection. This is not an unattended server-side collector.
4. Each run queries Beijing's current day, for plans created in the rolling last 7 days by default (also 14/30/90 selectable). Creation dates are saved in the snapshot and displayed separately from the statistical date. Older-created plans are not included, even if spending today. Version 1.9.4 sorts by stat_cost descending and reads only min(200, total) plans in at most two 100-row pages. It never requests page 3. The snapshot records this selection; displayed summaries represent only these rows, not the full upstream total. Missing metrics, zero results, duplicate IDs, unsafe numeric IDs, changing totals, crossing midnight or business/authentication refusal pause scheduling and preserve the previous snapshot. Only network timeouts and HTTP 502/503/504 receive up to two bounded retries; account identity is checked again on each retry. Resolve other problems and click enable again. No login or verification controls are bypassed.
5. The page checks plugin status every 3 seconds while visible and immediately updates controls from the start response. It shows the current page/row count and upload phase. Page execution has a 25-second wall-clock timeout in addition to the in-page fetch timeout; an interrupted worker is reported as paused. It refreshes displayed browser snapshots after a successful run, but does not overwrite a manually imported/query result. 读取最新快照 explicitly loads the latest saved result. Snapshot timestamps and date remain visible, including when they are old. Stop prevents subsequent runs; a save already in flight may complete.
6. A Chrome profile has one bound sync configuration. Changing BI or Chuangliang users requires re-enabling; separate operators should use separate Chrome profiles. Settings are stored in extension local storage, not synced across devices. Chrome alarms may be delayed; this is best-effort polling, not exact wall-clock scheduling.

Chrome scheduling reference: https://developer.chrome.com/docs/extensions/reference/api/alarms

## Data And Interpretation

- Enter the customer's actual settlement price per media registration. It has no preset value.
- `convert_cnt / active_register` is the observed return ratio; it is not necessarily the configured return rate, especially during delayed attribution.
- The theoretical break-even bid is `price / ratio`. Target ceiling is `price / ratio * (1 - margin / 100)`.
- This assumes `cpa_bid` targets exactly the conversion counted in `convert_cnt`, and all registrations are billable at the entered price. Deep-conversion and dual-bid campaigns need a different analysis.
- Profit is estimated commission minus media spend, without rebates, refunds or other expenses.
- Both registrations and returned conversions must meet the adjustable minimum sample. This threshold is a heuristic, not a statistical confidence interval.
- Date ranges including today are marked provisional. Yesterday can still have delayed attribution; review after the project's attribution window closes.
- Results describe the dates captured when data was fetched/imported, not subsequently edited date controls.
- Both manual queries and scheduled sync read at most two 100-row pages, by stat_cost descending. Missing pages, duplicate plan IDs and changing totals cause errors without replacing previous results. Excel import is unchanged. `getSum` is not mixed with list data; summaries are calculated from the same loaded rows.
- Excel import reads the first sheet with header row first. Required columns: `stat_cost`, `convert_cnt`, `active_register`, `cpa_bid`, or 消耗、转化数、注册数、出价. Use a plan-level period aggregate without totals or duplicate rows, and select its actual period before importing. Keep account/plan IDs formatted as text.

## Verification

`./mvnw package` passes all 123 Java tests. `node --test scripts/bid-monitor.test.cjs scripts/bid-sync.test.cjs scripts/bid-sync-worker.test.cjs` verifies formulas, pagination, scheduled runs, access failure handling and account binding. `node scripts/bid-monitor-ui-test.cjs` uses isolated headless Chrome with mocked APIs, verifying desktop/mobile layout, import, search, pagination and export.

Live comparison on 2026-09-03 using a user-supplied authorized request reproduced code -1 with a 46-character ff-request-id and code 0 after using the original 48-character format (Beijing timestamp + UUID hex + ff). Both Java and extension clients now generate this format. The successful response has data.list and data.page_info.total_count; deep_bid_type is an array. The list already includes stat_cost, convert_cnt, active_register and cpa_bid, so the separate getRealPromotionData request is not needed for these observed fields.

Historical 1.9.3 verification: creation-time sorting produced cross-page duplicates, so promotion_id descending was used for full collection. After fixing these differences and carrying total_count/total_page into subsequent extension requests, the actual extension parsing/validation core read all 4,656 rows over 47 pages in 54 seconds, without duplicates, for plans created 2026-08-28 through 2026-09-03 and statistics on 2026-09-03. This was a read-only local HTTP test, not a live end-to-end Chrome-to-BI upload. Browser orchestration and persistence are covered by isolated/mocked tests; the deployed user session still needs a successful browser sync. No supplied cookies are committed or persisted by the application.

Version 1.9.4 was verified with the live read-only API: 4,656 matching plans, two requests, 200 rows, approximately 3 seconds, using stat_cost descending. This is not a live Chrome-to-BI upload test. Ranking changes can still cause duplicates between pages; the original failure protections remain rather than requesting a third page.
