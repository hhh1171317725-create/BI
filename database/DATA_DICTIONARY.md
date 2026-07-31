# MySQL 数据字典

所有金额字段单位均为人民币元，日期按北京时间统计。数据库字段采用英文，字段备注和本文件提供中文口径。

## `report_sync_runs`

记录手动更新和每天 09:00 定时更新的执行结果。

| 字段 | 含义 |
|---|---|
| `id` | 同步任务主键 |
| `report_type` | `dhh` 大航海、`jd` 京东、`jd_low_activity` 京东低活、`all` 大航海与京东 |
| `trigger_type` | `manual` 手动、`scheduled` 定时 |
| `status` | `running` 运行中、`success` 成功、`failed` 失败 |
| `started_at` / `finished_at` | 开始时间 / 完成时间 |
| `row_count` | 本次写入或更新的底表行数 |
| `error_message` | 失败原因 |

## `dhh_daily_rows`

大航海日报原始底表。主要维度为日期、媒体、优化师、项目和任务。

| 字段 | 含义 |
|---|---|
| `business_date` | 业务日期 |
| `media` | 投放媒体 |
| `optimizer` | 优化师 |
| `project_name` | 根据任务名称归类的项目 |
| `task_name` | 原始任务名称 |
| `account_info` | 账户ID、账户名称和账户消耗组成的 JSON |
| `spend` | 总消耗 |
| `cash_spend` | 现金消耗 |
| `reward_spend` | 赠款消耗 |
| `estimated_commission` | 预估佣金 |
| `settlement_count` | 结算数 |
| `conversion_count` | 转化数 |
| `registration_count` | 注册数 |
| `row_hash` | 原始行去重哈希 |
| `synced_at` | 最近同步时间 |

现金利润口径：`estimated_commission - cash_spend`。

## `jd_daily_rows`

京东 CPA 日报原始底表。主要维度为日期、推广位、媒体、媒体账户、推客和优化师。

| 字段 | 含义 |
|---|---|
| `business_date` | 业务日期 |
| `promotion_id` / `promotion_name` | 推广位ID / 名称 |
| `media` | 投放媒体 |
| `media_account_id` / `media_account_name` | 媒体账户ID / 名称 |
| `promoter_username` | 推客用户名 |
| `optimizer` | 优化师 |
| `conversion_count` | 转化数 |
| `billable_conversion_count` | 计费转化数 |
| `deduplicated_order_count` | 去重订单总数 |
| `first_purchase_order_count` / `return_order_count` | 首购 / 回流订单总数 |
| `first_purchase_effective_orders` / `return_effective_orders` | 首购 / 回流有效订单数 |
| `first_purchase_invalid_orders` / `return_invalid_orders` | 首购 / 回流无效订单数 |
| `first_purchase_completed_orders` / `return_completed_orders` | 首购 / 回流已完成订单数 |
| `spend` | 总消耗 |
| `estimated_compensation` | 条件内预估赔付金额（当日） |
| `first_purchase_estimated_commission` / `return_estimated_commission` | 首购 / 回流预估佣金 |
| `first_purchase_actual_commission` / `return_actual_commission` | 首购 / 回流实际佣金 |
| `row_hash` | 原始行去重哈希 |
| `synced_at` | 最近同步时间 |

## `jd_daily_metrics`

京东常用计算指标视图：

- `effective_order_count`：首购有效订单数＋回流有效订单数；
- `effective_first_purchase_rate`：首购有效订单数÷有效订单数；
- `estimated_commission`：首购预估佣金＋回流预估佣金；
- `actual_commission`：首购实际佣金＋回流实际佣金；
- `estimated_profit`：预估佣金＋预估赔付－消耗；
- `actual_profit`：实际佣金＋预估赔付－消耗。

## `jd_low_activity_plan_rows`

京东低活任务明细底表。每次同步先校验全部分页数据，再在同一事务内替换指定日期范围，失败时保留原数据。上游 `ad_cost` 以千分之一元返回，入库时统一换算为人民币元。

| 字段 | 含义 |
|---|---|
| `business_date` | 业务日期 |
| `admin_user` | 上游管理员 |
| `task_name` | 上游任务/广告主用户 |
| `advertiser_id` / `advertiser_name` | 账户 ID / 名称 |
| `plan_id` / `plan_name` | 计划 ID / 名称；上游未返回独立计划字段时暂用账户信息回退 |
| `has_plan_dimension` | 是否确实包含独立计划字段 |
| `spend` / `amount` | 消耗 / 上游金额 |
| `impressions` / `clicks` | 展现 / 点击 |
| `conversions` | 转化数 |
| `successful_conversions` / `filtered_conversions` | 成功转化数 / 过滤转化数 |
| `valid_parent_orders` / `valid_order_uv` | 有效父订单数 / 有效订单 UV |
| `valid_click_uv` | 有效点击 UV |
| `unit_price` | 上游单价 |
| `commission` / `first_day_commission` | 佣金 / 首日佣金 |
| `low_commission_orders` | 低佣订单数 |
| `t3_orders` / `total_orders` | T3 订单数 / 总订单数 |
| `upstream_profit` / `upstream_simulated_profit` | 上游利润 / 上游模拟利润，仅用于核对 |
| `profit_gap` / `gap_ratio` | 利润差 / 差值比例 |
| `budgeted_gross_margin_rate` | 预算毛利率 |
| `media_type` | 媒体类型 |
| `league_account` / `customer_agent` | 联盟账户 / 客户代理 |
| `remark` | 上游备注 |
| `raw_json` | 原始上游行 JSON，便于后续补充字段 |
| `row_hash` | 明细去重哈希 |
| `synced_at` | 最近同步时间 |

页面利润口径：`commission - spend`。ROI 口径：`commission ÷ spend`。
