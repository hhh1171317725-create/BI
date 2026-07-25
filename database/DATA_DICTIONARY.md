# MySQL 数据字典

所有金额字段单位均为人民币元，日期按北京时间统计。数据库字段采用英文，字段备注和本文件提供中文口径。

## `report_sync_runs`

记录手动更新和每天 09:00 定时更新的执行结果。

| 字段 | 含义 |
|---|---|
| `id` | 同步任务主键 |
| `report_type` | `dhh` 大航海、`jd` 京东、`all` 两者 |
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
- `estimated_commission`：首购预估佣金＋回流预估佣金；
- `actual_commission`：首购实际佣金＋回流实际佣金；
- `estimated_profit`：预估佣金＋预估赔付－消耗；
- `actual_profit`：实际佣金＋预估赔付－消耗。

## `jd_account_ratios`

京东营销回传策略中的媒体账户扣量配置。系统按媒体账户 ID 与京东日报关联。

| 字段 | 说明 |
| --- | --- |
| `account_id` | 京东媒体账户 ID，主键 |
| `account_name` | 京东媒体账户名称 |
| `config_ratio` | API 的 `configRatio`，例如 `15` 在报表显示为 `15%` |
| `callback_event_type` | 回传事件类型；同账户多条策略时优先订单事件 `4` |
| `status` | 策略状态，`1` 为启用 |
| `source_updated_at` | 京东策略更新时间戳（毫秒） |
| `fetched_at` | 本系统拉取时间 |
