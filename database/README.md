# MySQL 数据库初始化

先在宝塔或 phpMyAdmin 中创建并选中目标数据库，再导入 `schema.sql`。脚本不会创建或切换数据库，因此普通数据库用户也可以执行。脚本会建立：

- `dhh_daily_rows`：大航海日报底表；
- `jd_daily_rows`：京东日报底表；
- `jd_low_activity_plan_rows`：京东低活任务明细底表，保留上游原始字段并支持独立计划维度；
- `report_sync_runs`：手动及定时同步记录；
- `report_users`：系统登录用户、角色及会话版本；
- `jd_daily_metrics`：京东有效订单、佣金和利润计算视图。

所有表和字段均带有中文 `COMMENT`，可在 phpMyAdmin 的表结构页面直接查看；完整口径见 [`DATA_DICTIONARY.md`](DATA_DICTIONARY.md)。

脚本可重复执行，不会删除已有数据。

应用启动时也会自动创建 `report_users`。用户表首次为空时，会将当前
`REPORT_USERNAME` / `REPORT_PASSWORD` 创建为首个管理员；密码只保存为带随机盐的
PBKDF2-SHA256 哈希。现有数据库也可以单独导入：

```bash
mysql -h 数据库地址 -P 3306 -u 数据库用户 -p 数据库名称 < database/add_report_users.sql
```

如果已经导入过没有备注的旧版表，不要删除表，直接在当前数据库中导入 `add_comments.sql`，即可为现有表和字段补充中文备注。

已部署的数据库还应一次性导入 `add_jd_query_indexes.sql`，为京东账户 ID 筛选补充联合索引：

```bash
mysql -h 数据库地址 -P 3306 -u 数据库用户 -p 数据库名称 < database/add_jd_query_indexes.sql
```

升级已有系统时，导入低活报表迁移脚本。该脚本会扩展同步日志类型并创建低活明细表，不会删除现有数据：

```bash
mysql -h 数据库地址 -P 3306 -u 数据库用户 -p 数据库名称 < database/add_jd_low_activity.sql
```

应用启动时也会自动检查并创建低活明细表；生产环境仍建议先执行迁移脚本，以便提前确认数据库用户具备建表和修改字段权限。

```bash
mysql -h 数据库地址 -P 3306 -u 数据库用户 -p 数据库名称 < database/schema.sql
```

建议为应用创建只能访问目标报表数据库（例如 `BI`）的独立 MySQL 用户，不要在代码或 Git 中保存数据库密码。连接配置参考 `mysql.env.example`。
