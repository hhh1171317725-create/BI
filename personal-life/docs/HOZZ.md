# Hozz 手机连接与宝塔部署

Hozz 在 iPhone 上读取授权的 Apple Health，再主动推送自己的 HTTPS 服务；不使用云端 HealthKit 读取接口或 Garmin 私有 API。

## 首次部署

`https://huanghaha.fun/life-health.html` 是 BI 工具入口，不是健康服务。建议新增 `health.huanghaha.fun`，**示例域名不代表已上线**。先解析到服务器，在宝塔新增独立网站并申请 SSL，不覆盖 BI 站点。

服务器需 Docker Engine、Compose v2。每条命令独立运行：

```sh
cd /www/wwwroot/BI
git pull --ff-only origin main
cd personal-life
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" -w /work node:24-bookworm-slim node scripts/configure.mjs https://health.huanghaha.fun
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl -f http://127.0.0.1:3100/api/healthz
```

配置脚本生成各不相同的强密码/密钥，仅写 `.env`（权限600），不输出日志。**已有 `.env` 时跳过初始化**，保留已经使用的数据库密码与用户，在服务器私有编辑器核对 APP_ORIGIN；不要重新生成数据库密码。其他域名需替换上述域名。

健康站点按 `deploy/nginx.conf.example` 反代 `http://127.0.0.1:3100`，替换示例域名、证书路径和日志目录。强制 HTTPS、覆盖 X-Forwarded-Proto、上传上限5MB、关闭健康访问日志。在宝塔测试并重载，不假设 nginx.service 可用。不重启 Java，不开放 PostgreSQL 公网端口。

公网 `/api/healthz` 返回 `{"status":"ok"}` 后打开根页面。使用服务器 `.env` 中 BOOTSTRAP_USERNAME/BOOTSTRAP_PASSWORD 登录；已有用户不被重启改密码。BI 入口保存实际健康域名，「数据同步」页可复制接收地址。DNS/证书/服务未就绪前不在手机开启上传。

## Hozz 设置

Automatic → Add a destination → Web address：

| 项目 | 填写 |
| --- | --- |
| Web address | 实际健康域名加 `/api/health/ingest` |
| Authorization header | `Bearer ` 加 .env 中 INGEST_API_KEY，一个空格，不写 `Authorization:` |
| 名称 | 自己的健康系统 |
| Format | **JSON**，不是 NDJSON、Metrics JSON、CSV 或 HAE 兼容模式 |
| How often | When new data arrives |
| Starting point（若有） | 首次 Start from 7 days ago，补历史可改更早 |
| Request size（若有） | 1 MB，服务默认硬上限5MB、每批5000条记录 |
| Timeout（若有） | 可选120秒；服务事务上限60秒，超时重发安全 |

先测试连接，再保存并在解锁时同步一次。连接测试不产生健康测量或更新健康上传时间；连接成功不代表已上传数据。检查工作台批次、记录数、日期和完整性。不要把密钥放 URL、入口、聊天、截图、Git 或公开日志；浏览器不会获取上传密钥。

## 实现边界

适配依据 [Hozz 官方 schema v1 文档](https://github.com/brandomoore/Hozz/blob/c5baa3b2aa1770513ea1c5844acf05d40e4a8f6e/docs/delivery-schema.md) 和同提交 DeliveryProbe.swift 核对。支持JSON数组与独立连接测试对象。真实手机样本尚未拿到，首次同步需核对版本和结果。

- 原始批次留存，HealthSourceRecord保存稳定身份、版本和删除标记。新版本替换投影，重复/旧批次不会覆盖最新值或复活删除数据。用户事务锁和数据库唯一约束保证并发幂等。
- 映射步数、心率、静息心率、活动热量、体重、睡眠阶段和运动时长，校验单位；运动热量无已确认字段时留空。Garmin Connect遵循Garmin来源优先，不与iPhone步数相加。
- Apple睡眠0/1/2/3/4/5分别为卧床/未分阶段睡眠/清醒/Core/Deep/REM。实际睡眠区间取并集、减清醒，不叠加重叠阶段、不计卧床。依据 [Apple睡眠说明](https://developer.apple.com/documentation/healthkit/hkcategoryvaluesleepanalysis)。只有In Bed时暂无实际睡眠。
- 同来源/来源包/设备睡眠间隔不超过90分钟归为一个会话；间隔不算睡眠，更大间隔分开。这是产品分组规则，不是医学诊断。UTC存储、上海展示，归属最后实际睡眠结束日。
- 拆分批次、后到数据、更新和删除重算附近睡眠与受影响日汇总，删空清理旧汇总。
- 未支持类型/单位/跨本地日累计记录仅留存，显示「部分记录仅留存」，不猜测分摊；未知版本待映射；无效批次422并保留错误批次，不半写入。
- typeCoverage不是测量，按observedAt保存最新报告；历史未完整或无报告时提示，最新已收到日期不等于历史完整。
- 「重新处理」允许以新版映射重建相同版本的非删除记录，但不能覆盖更新的来源版本或复活已删除的数据；删除空汇总同步刷新。
- 导出包含原始批次、版本和删除标记；删除个人数据会清除这些记录并撤销会话。

Hozz是TestFlight测试软件，iOS决定后台运行，锁屏/强制退出影响同步，不承诺实时或永久免费。保留设备原始数据和独立备份，参考 [官方beta说明](https://github.com/brandomoore/Hozz/blob/c5baa3b2aa1770513ea1c5844acf05d40e4a8f6e/docs/beta-testing.md)。

401检查Bearer/密钥；415选JSON；413降低批次大小；422核对格式/版本；429稍后重试；5xx检查服务/数据库。不要通过关闭鉴权解决上传失败。
