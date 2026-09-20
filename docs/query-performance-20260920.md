# 加载与交互性能优化（2026-09-20）

- 历史区间先计算一次计划累计转化与消耗，然后按日期关联单价和 gap。移除每个日期重新筛选其余全部记录、重复累计的工作，报表和策略测试共用这条路径。
- 复用日期格式器与中文数字排序比较器，避免逐行创建 Intl 对象。
- 翻页、展示列调整复用排序结果；数据或排序条件变化时重新排序。搜索文本按当前分析数据预建索引，保留批量 ID 精确匹配。
- 报表汇总和策略面板在输入数据未变化时复用结果，减少翻页触发的全量扫描。
- 历史转化接口缓存最多两个查询结果、有效期 15 秒；同一来源与时间范围的并发请求共用查询。鉴权仍在每次接口调用时执行。归档事务提交后清除本进程缓存，多实例间最多存在 15 秒缓存延迟。完整历史明细不缓存，避免保留多份大体积 JSON。

## 验证

本地 Windows / Node，15 天、6,000 条模拟计划日记录，对比提交 `f0a1ae7`：历史计算约 1,090 ms → 158 ms（6.9 倍）。核对累计转化、累计消耗、单价、gap、佣金、预估赔付、ROI，结果一致。单次本地测量不代表生产网络或数据库端到端耗时。

复现：`node scripts/bid-performance-benchmark.cjs`。

- `node --test scripts/bid-performance.test.cjs scripts/bid-monitor.test.cjs scripts/bid-history-loading.test.cjs`：49 项通过。
- `node scripts/bid-monitor-ui-test.cjs`：桌面、手机、筛选、翻页、策略、历史与预警交互通过。
- Maven：BidHistoryReadTest、BidHistoryServiceTest、QueryResultCacheTest、BidHistoryControllerTest 通过；覆盖缓存复用、来源和日期隔离、归档后失效。

部署同时更新后端 JAR 与前端资源，无需数据库结构迁移。
