-- 对已部署的数据库执行一次。账户 ID 精确筛选 + 日期范围查询会使用该联合索引。
ALTER TABLE `jd_daily_rows`
  ADD KEY `idx_jd_account_id_date` (`media_account_id`, `business_date`);
