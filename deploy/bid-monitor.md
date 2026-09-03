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

Only restart after a successful build. Administrators can enable `bidMonitor` for operators in the existing tool permission dialog. All endpoints enforce login and tool permission. Manual query cookies remain request-scoped. Server synchronization saves encrypted credentials only after explicit confirmation; snapshots contain only allowlisted plan data. Snapshot reads always use the current website user; configuration changes also verify the website user bound when the page was loaded.

## Server Scheduled Updates (2.1.0)

1. No extension is required. Stop the old extension's bid synchronization, or disable that extension if it is no longer needed. The new page talks only to the BI backend.
2. Enter Cookie, client-user and main-user-id from the same authorized Chuangliang session. Each operator uses their own website account and their own upstream credentials. No sample or historical credentials are automatically saved.
3. Choose the interval (5/10/15/30/60 minutes). Creation scope is now fixed to Beijing today minus three days through today, inclusive (four calendar dates): September 4 reads September 1-4. Existing saved schedules automatically adopt this scope. Click 保存并启用 and confirm encrypted storage. A blank Cookie preserves the saved value for that same upstream identity; changing either identity requires a fresh Cookie. Plaintext is cleared from the field after saving and is never stored in browser storage.
4. The server queues a run within approximately 5 seconds and persists the schedule in MySQL. Chrome can be closed. 立即同步 queues another run; 停止同步 cancels future scheduling and invalidates in-flight commits. 清除保存凭据 additionally removes the saved encrypted Cookie but preserves the last successful snapshot.
5. Each run reads Beijing's current-day statistics for ALL matching plans, in 100-row pages ordered by promotion_id descending for stable pagination. The former 200-row cutoff is removed. The UI still defaults to spend descending. Creation range and statistical range are separate: older-created plans outside the four-date scope are excluded even if they spend today. A complete snapshot requires exactly upstreamTotal rows, with no duplicates. Safety ceilings (one million rows or 15 MB serialized UTF-8 snapshot) fail explicitly instead of truncating or overwriting old data.
6. Zero rows, changed totals, duplicates, missing/invalid metrics, failed authentication, network errors or revoked website tool access pause synchronization without replacing the last successful snapshot. Correct the issue and enable again. Login, verification and upstream permissions are not bypassed.
7. A bounded two-worker pool performs collection outside the Spring scheduler thread. A database row lock and revision token guard each claim/commit. Interrupted jobs have a three-minute lease before recovery. Successful page progress renews the lease, checks ownership/permission and exposes the collected/total count; stopping prevents further pages and commits. Permission and Beijing date are checked again before saving. Enabled server jobs reject old extension snapshot uploads to avoid overwrites.
8. Visible pages poll status every 5 seconds, but scheduling is independent of the page. Existing manually queried/imported data is not automatically overwritten. 读取最新快照 explicitly loads the latest owned snapshot; its timestamp and statistical date remain visible.

### Credential Storage And Deployment

- The database user needs CREATE TABLE permission for bid_monitor_server_sync and bid_monitor_snapshots. Settings, due time and status are persisted per website user. API responses never include encrypted or plaintext Cookie values.
- AES-256-GCM uses a random nonce per save and binds ciphertext to the website user ID. The 32-byte key is created in DHH_RUNTIME_DIR/bid-monitor.key (default .runtime/bid-monitor.key). On POSIX it is created owner-only. The service user must be able to read/write this directory. Keep it outside the Nginx static root.
- Back up the key securely alongside database backups. Do not commit it or delete the runtime directory during deployment. If the key is lost, restore it or re-enter credentials for affected users. Multiple application instances must share the same key and MySQL database.
- Use HTTPS for the website and keep its normal authentication enabled. Encryption at rest is not protection against a compromised application server.
- Credentials remain subject to expiry and upstream access policy. Local authorized HTTP success does not guarantee that the deployment server's IP will be accepted. A server-side refusal requires checking the provider's permissions or supported integration, not fingerprint evasion.

## Data And Interpretation

- In 任务结算价格, add a task name, a substring to match in the upstream account name, and its customer settlement price per registration. Save task prices. Rules are stored per website user with optimistic conflict detection and never change another user's rules. Up to 50 tasks are supported. Matching is case-insensitive; zero matches, multiple matches or invalid prices suppress profit/ROI for those plans rather than silently choosing a price. There is no global fallback price. The task filter, search, table and CSV use the same resolved prices.
- `convert_cnt / active_register` is the observed return ratio; it is not necessarily the configured return rate, especially during delayed attribution.
- The theoretical break-even bid is `price / ratio`. Target ceiling is `price / ratio * (1 - margin / 100)`.
- This assumes `cpa_bid` targets exactly the conversion counted in `convert_cnt`, and all registrations are billable at the entered price. Deep-conversion and dual-bid campaigns need a different analysis.
- Projected cost assumes each returned conversion costs exactly its current cpa_bid: projectedCost = bid * conversions. Projected revenue = registrations * task price. Projected profit = projected revenue - projected cost. Projected ROI = projected revenue / projected cost, where 1 means break-even. This is a hypothetical estimate, not the platform's configured ROI or a prediction that bid equals realized cost. Zero bid/zero conversions/missing or abnormal return rates do not produce projected profit/ROI. The separate actual-spend profit uses registrations * price - actual spend; it too assumes billable registrations and excludes rebates, refunds and other expenses.
- Both registrations and returned conversions must meet the adjustable minimum sample. This threshold is a heuristic, not a statistical confidence interval.
- Date ranges including today are marked provisional. Yesterday can still have delayed attribution; review after the project's attribution window closes.
- Results describe the dates captured when data was fetched/imported, not subsequently edited date controls.
- Both manual queries and scheduled sync fetch every page, by promotion_id descending. Missing pages, duplicate plan IDs and changing totals cause errors without replacing previous results. Excel import is unchanged. `getSum` is not mixed with list data; summaries are calculated from the currently filtered rows. Aggregate projected ROI is total projected revenue divided by total projected cost, not an average of plan ROIs. If any selected row lacks a required value, that aggregate is unavailable instead of treating it as zero.
- Excel import reads the first sheet with header row first. Required columns: `stat_cost`, `convert_cnt`, `active_register`, `cpa_bid`, or 消耗、转化数、注册数、出价. Use a plan-level period aggregate without totals or duplicate rows, and select its actual period before importing. Keep account/plan IDs formatted as text.

## Verification

Version 2.1.0: the actual Java collection method was tested locally with authorized credentials held only in memory. It fetched 3,335 of 3,335 matching plans over 34 pages in approximately 79 seconds, with creation dates 2026-08-31 through 2026-09-03 and statistics on 2026-09-03. A prior attempt failed before producing a complete snapshot; its exact cause was not captured. No production credentials, schedules or snapshots were saved by these probes. Deployment-server acceptance still needs verification. Automated tests cover September 4 -> September 1-4, month/year boundaries, full pagination, lease renewal/cancellation, owner-scoped task prices, ambiguous matches, and the projected profit/ROI formulas.

Version 2.0.0 verification on 2026-09-03: the new Java server collection method itself was invoked locally with the authorized sample credentials held only in memory. It fetched the top 200 from 4,669 matching plans in approximately 4 seconds through ordinary Java HTTP, without a browser or extension. This did not save credentials, enable a schedule or write a production snapshot. It does not establish acceptance of the deployment server's IP. The package build passed 135 Java tests; 29 JavaScript tests and the no-extension desktop/mobile UI test also passed.

Run `./mvnw package` for Java tests, including encrypted storage, owner binding, server task collection, stop/reconfigure guards, permission revocation and failure preservation. `node --test scripts/bid-monitor.test.cjs scripts/bid-sync.test.cjs scripts/bid-sync-worker.test.cjs` verifies formulas and legacy extension behavior. `node scripts/bid-monitor-ui-test.cjs` uses isolated headless Chrome with mocked APIs and no extension, verifying desktop/mobile layout, import, search, pagination, export and server synchronization controls. Production server access still requires deployment and a successful run using current authorized credentials.

Live comparison on 2026-09-03 using a user-supplied authorized request reproduced code -1 with a 46-character ff-request-id and code 0 after using the original 48-character format (Beijing timestamp + UUID hex + ff). Both Java and extension clients now generate this format. The successful response has data.list and data.page_info.total_count; deep_bid_type is an array. The list already includes stat_cost, convert_cnt, active_register and cpa_bid, so the separate getRealPromotionData request is not needed for these observed fields.

Historical 1.9.3 verification: creation-time sorting produced cross-page duplicates, so promotion_id descending was used for full collection. After fixing these differences and carrying total_count/total_page into subsequent extension requests, the actual extension parsing/validation core read all 4,656 rows over 47 pages in 54 seconds, without duplicates, for plans created 2026-08-28 through 2026-09-03 and statistics on 2026-09-03. This was a read-only local HTTP test, not a live end-to-end Chrome-to-BI upload. Browser orchestration and persistence are covered by isolated/mocked tests; the deployed user session still needs a successful browser sync. No supplied cookies are committed or persisted by the application.

Version 1.9.4 was verified with the live read-only API: 4,656 matching plans, two requests, 200 rows, approximately 3 seconds, using stat_cost descending. This is not a live Chrome-to-BI upload test. Ranking changes can still cause duplicates between pages; the original failure protections remain rather than requesting a third page.
