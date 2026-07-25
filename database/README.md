# MySQL 数据库初始化

`schema.sql` 会创建数据库 `marketing_reports`，并建立：

- `dhh_daily_rows`：大航海日报底表；
- `jd_daily_rows`：京东日报底表；
- `report_sync_runs`：手动及定时同步记录；
- `jd_daily_metrics`：京东有效订单、佣金和利润计算视图。

脚本可重复执行，不会删除已有数据。

```bash
mysql -h 数据库地址 -P 3306 -u 数据库用户 -p < database/schema.sql
```

建议为应用单独创建仅能访问 `marketing_reports` 的 MySQL 用户，不要在代码或 Git 中保存数据库密码。
