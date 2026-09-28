# 单机网站的 MySQL 磁盘治理

适用本项目当前宝塔 MySQL 5.7：仅供本站使用，管理员已确认没有复制、Canal 或 binlog 增量恢复需求。
若未来启用这些功能，应重新规划日志保留和磁盘容量，不应沿用禁用方案。

## 一次性服务器操作

1. 在宝塔终端执行 `mysql -u root -p`（数据库 root 密码）。关闭二进制日志前清理旧文件：

```sql
PURGE BINARY LOGS BEFORE NOW();
SHOW BINARY LOGS;
exit;
```

该操作清除符合条件的已关闭日志，不删当前正在写入的日志或业务表；旧日志的时间点恢复能力随之失去。
不要用文件管理器删除 binlog，不要执行 RESET MASTER，不要删除 ibdata1、ib_logfile 或业务表文件。

2. 在宝塔 MySQL 配置修改中先备份原配置。找到 `[mysqld]` 段，注释其中启用二进制日志的 `log-bin=...` / `log_bin=...` 行，并在该段添加一次：

```ini
skip-log-bin
```

保持其他配置不变。保存后通过宝塔重启 MySQL（网站数据库请求会短暂中断）。
仅部署网站代码不会修改数据库服务器配置。

3. 重新登录 MySQL 验证：

```sql
SHOW GLOBAL VARIABLES LIKE 'log_bin';
```

必须是 `OFF` 才说明成功；若仍是 ON，检查其他配置文件或启动参数中的 log-bin 覆盖，不要继续手工删文件。
回到终端执行 `df -h /` 和 `sh /www/wwwroot/BI/scripts/check-disk-space.sh` 检查释放情况。
旧的当前 binlog 可能保留，不等于仍在增长；禁用本身不会自动删除已有日志。

## 持续维护

- 在宝塔计划任务配置每日数据库全量备份，优先异机/对象存储。本机仅留少量近期备份，避免备份成为新的占用来源。
- 关闭 binlog 后可从全量备份恢复，但不能恢复到备份之后任意时间点；普通 InnoDB 崩溃恢复日志仍保留。
- 配置磁盘使用率告警（例如 80%），定期检查业务表、备份、网站日志。数据库仍会随历史数据增长，40 GB 并非无限容量。
- 本次代码将出价原始行和归档行从每日全删全插改成增量比对：新记录插入、变化记录更新、不变记录不写、消失记录按完整主键删除。完整上游字段继续保存，未增加历史数据自动删除策略。
- 不能据此声称 binlog 已在生产关闭，或预估实际节省量；需要部署后查看服务器变量与磁盘增长。

官方说明：https://dev.mysql.com/doc/mysql-replication-excerpt/5.7/en/replication-options-binary-log.html
