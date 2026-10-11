# AI 个人生活管理系统 / Personal Life

这是 BI 工具中心下的**独立健康服务**。Next.js 全栈、PostgreSQL 和单独的登录账户，与现有 Java BI、MySQL、端口 8765 隔离，不替换现有服务。

## 当前交付边界

- **Hozz JSON v1 已接入**：健康指标、睡眠区间计算、稳定 ID 去重、版本/删除、读取完整性。详见 [Hozz 手机连接与部署](docs/HOZZ.md)。真实手机与云服务器端到端仍需验证；示例域名不代表已经上线。

- 已实现：独立认证、原始 JSON 安全接收、批次幂等、规范化适配器契约、数据库唯一约束、来源冲突处理、日汇总、历史补传、睡眠/运动/体重/心率统计、中文深色仪表盘、同步监控、失败重处理、授权 AI 查询、数据导出和删除、迁移、备份及 Docker 配置。
- **Health Auto Export 尚未取得脱敏真实样本，该格式默认只保存原始批次，返回 `202 awaiting_mapping`，不猜测字段或写入示例健康数据。** 不影响已单独核对的 Hozz JSON v1 适配器。
- `normalized-v1` 是本项目的内部适配契约和自动化测试格式，**不是** Health Auto Export 官方格式。默认关闭。真实格式验证前，不要在生产开启它来冒充已完成设备接入。
- 真实 Apple Health 同步仍受 iOS 解锁状态、后台调度、Health Auto Export 版本/权限影响，不保证实时。此服务不直接访问 Apple/Garmin 私有接口，不模拟登录 Garmin。
- AI 使用官方 SDK 的 Responses API、`store:false`；需要服务端 Key、模型配置及用户授权。OpenAI 网络可达性由部署环境决定。

## 目录与架构

```text
Apple Health → Hozz JSON / Health Auto Export → /api/health/ingest
                                   ↓ 独立 ingest key
                             HealthRawImport（隔离原始数据）
                                   ↓ 已验证适配层
                         HealthSample / SleepSession / Workout
                                   ↓ 来源优先级、重叠排除
                            DailyHealthSummary
                                   ↓ 登录会话
                         Dashboard / Sleep / Fitness
                                   ↓ 每次单独授权，最多31天摘要
                              OpenAI Assistant
```

主要文件：

- `src/lib/domain.ts`：规范化契约、时间/单位验证、指纹、来源选择与重叠规则。
- `src/lib/ingest.ts`：原始隔离、事务写入、数据库去重与日志。
- `src/lib/analytics.ts`：UTC/上海时区、睡眠归属、日统计、心率数据库聚合。
- `src/lib/api.ts`：接口分发、统一错误、权限、导出/删除、设置。
- `src/lib/security.ts`：scrypt 密码、随机会话、独立接收密钥、持久化限流。
- `src/lib/assistant.ts`：受控意图查询、最小摘要、官方 OpenAI SDK。
- `src/lib/export.ts`：分页、背压控制、数据库一致性快照导出。
- `src/components/workspace.tsx`：响应式六页工作台和 Recharts。
- `prisma/schema.prisma`、`prisma/migrations/`：数据库模型和可部署迁移。
- `deploy/`：容器启动和独立 Nginx 站点示例。
- `docs/API.md`、`docs/HEALTH_AUTO_EXPORT.md`、`docs/TESTING.md`：接口、接入和验证说明。

## Linux / 宝塔部署

前提：Docker Engine、Docker Compose v2；有证书的独立域名；服务器能访问所选 OpenAI 服务。**不需要重新编译或重启现有 Java BI。** 现有 Nginx 负责静态 BI 页面时，拉取代码后新工具卡片即可生效。

在服务器现有仓库中执行，每条命令独立运行：

```sh
cd /www/wwwroot/BI
git pull --ff-only origin main
cd personal-life
cp -n .env.example .env
chmod 600 .env
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 24
nano .env
```

分别生成并填入数据库密码、`INGEST_API_KEY`、独立登录密码。不要重复使用同一个密钥。`.env.example` 的 `CHANGE_ME` **不能用于运行**。

必须配置：

| 变量                 | 内容                                                                |
| -------------------- | ------------------------------------------------------------------- |
| `APP_ORIGIN`         | 实际 HTTPS 地址，例如 `https://health.你的域名`；不带路径或末尾斜杠 |
| `POSTGRES_PASSWORD`  | 随机 URL-safe 密码，建议上面的 hex 值                               |
| `BOOTSTRAP_USERNAME` | 私人登录用户名                                                      |
| `BOOTSTRAP_PASSWORD` | 至少14字符强密码，建议随机生成                                      |
| `INGEST_API_KEY`     | 32随机字节的64字符hex值；与登录密码不同                             |
| `OPENAI_API_KEY`     | 可选；未配置时助手显示配置缺失，不伪造答案                          |
| `OPENAI_MODEL`       | 你账户可用的模型名称；不硬编码默认模型                              |
| `WEB_PORT`           | 默认3100；有冲突则更换，并同步改Nginx上游端口                       |

Compose 自动构造 `DATABASE_URL`，不将 PostgreSQL 映射到宿主机端口。环境变量中的数据库用户名/名称保持简单字母数字下划线，密码用 hex，避免 URI 编码歧义。

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 web
curl -f http://127.0.0.1:3100/api/healthz
```

首次启动自动执行 `prisma migrate deploy` 和安全用户初始化。**已有用户密码不会因容器重启被覆盖。** 配置错误会使初始化失败，而不是创建弱密码账户。服务默认只绑定 `127.0.0.1`，公网必须经 HTTPS 反向代理。

### 宝塔

1. 给新健康子域名解析 A/AAAA 记录，新建一个**单独的网站**，申请有效 SSL 证书。
2. 不要修改现有 BI 站点的根目录、8765 上游或数据库。
3. 关闭新站点中冲突的默认 `location /`，参考 `deploy/nginx.conf.example` 设置反代 `http://127.0.0.1:3100`。
4. 必须覆盖 `X-Forwarded-Proto` 为 `https`，开启 HTTP→HTTPS，并关闭健康站点访问日志。上传限制5MB。
5. 在宝塔中测试并保存新站点配置、重载 Nginx。宝塔的 Nginx 不一定由 `nginx.service` 管理，优先使用面板重载按钮。
6. 用浏览器登录新地址，再在 BI 的「工具中心 → AI 个人生活管理」填写该 HTTPS 地址。

### 普通 Linux Nginx

将示例另存为一个新 vhost，替换域名、证书路径、日志目录和端口，先检查配置，再重载你实际使用的 Nginx 服务：

```sh
nginx -t
systemctl reload nginx
```

不要把示例配置直接覆盖到现有 BI 站点；首次签发证书使用已有 ACME/面板流程。此项目不自动申请证书，也不假设服务器现有证书路径。

### 更新

先备份，再拉代码、构建。迁移会自动运行。不要删除数据卷。

```sh
cd /www/wwwroot/BI/personal-life
sh scripts/backup.sh
cd /www/wwwroot/BI
git pull --ff-only origin main
cd personal-life
docker compose up -d --build
docker compose logs --tail=100 web
```

## 本地开发

Node.js 22或更新的受支持版本、PostgreSQL 17+。为本地测试创建独立数据库和 `.env`，将 `DATABASE_URL` 指向该测试库。仅在本地设：

```dotenv
APP_ORIGIN=http://127.0.0.1:3000
ALLOW_INSECURE_LOCAL=true
```

普通 `tsx` 脚本通过下面的 `node --env-file` 加载环境；Next.js 自动加载 `.env`：

```sh
npm ci
npm run db:generate
node --env-file=.env node_modules/prisma/build/index.js migrate deploy
node --env-file=.env node_modules/tsx/dist/cli.mjs scripts/bootstrap.ts
npm run dev
```

生产必须关闭 `ALLOW_INSECURE_LOCAL`。本地接收密钥同样需要独立强密钥。

## 数据规则

- 时间列为 PostgreSQL `timestamptz`，规范化输入必须带 UTC offset；存储按 UTC，展示固定上海时区。
- 睡眠按醒来当地日期归属；午睡与夜间睡眠可分别保留。每日卡片显示当日全部已选择的睡眠区间，入睡/起床显示最长区间。睡眠阶段只展示原始区间，不生成所谓医学睡眠评分。
- 步数与活动消耗，每个当地日/指标只选一个来源（用户可改精确名称优先级）。**不跨来源累加**，也不拼接低优先来源填满时间空洞。无法确定的部分保持缺失。
- 同来源重叠区间优先最新记录，再选较长区间，保留不重叠部分；这是保守冲突规则，不声称复刻 Apple Health 的所有内部聚合算法。
- 累计记录结束于次日00:00归属前一日；跨多个当地日的累计记录必须经真实适配器按可验证的原始粒度拆分，否则拒绝，不能平均摊分编造。
- 未测量是 `null`，真实测量的0才是0。图表缺失日期显示断点。心率使用数据库按日/来源聚合，避免向浏览器传一生的高频样本。
- 批次以用户、来源、payloadHash唯一；记录以用户、UTC规范化内容fingerprint唯一。修正值保留可审计新记录，统计在重叠处采用最新值。
- 导出包含当前用户原始数据/指标/日志/对话，不含密码哈希或会话密钥。分页流式快照最大120秒，超时需重试；极大数据集可用数据库备份。
- 删除需登录、同源请求和再次验证密码，撤销所有登录，保留账户，停止现有AI授权。手机仍持有有效接收Key时，未来上传可再次产生数据；要停用同步请先停手机自动化并轮换/清空服务器接收Key。

## 备份、恢复与运维

```sh
cd /www/wwwroot/BI/personal-life
sh scripts/backup.sh
docker compose logs --tail=100 web
docker compose logs --tail=100 postgres
```

备份位于 `backups/`，权限由 `umask 077` 保护。安排服务器计划任务运行脚本并将加密副本离机保存；备份包含敏感数据，不提交Git，不公开下载。Docker命名卷持久化数据；**不要运行生产 `docker compose down -v`**。

恢复流程：先在隔离测试库验证备份。正式恢复是覆盖性操作，需要暂停写入、再次备份并确认目标库。可使用 `docker compose exec -T postgres pg_restore`，指定容器内实际用户名/数据库。本文不提供无需确认就清空生产数据库的脚本。

安全边界：本应用不是医疗系统认证产品。宿主机、Docker管理员和备份持有人仍能访问数据；建议磁盘/备份加密。生产关闭PostgreSQL语句/参数详细日志，不开启Nginx请求体调试；`.env`仅管理员可读。API普通错误日志只有随机请求ID、状态和错误代码。浏览器没有OpenAI或接收Key。

密码找回：此私人系统没有公开注册或邮件重置入口；可由服务器管理员使用受控维护脚本更新用户密码并撤销全部会话。不要依赖修改 `BOOTSTRAP_PASSWORD` 自动覆盖已有密码。接收Key轮换：更新服务器 `.env` 和手机请求头，重新创建web容器。旧Key立即失效；登录密码与会话互不共用。

## 测试与扩展

详细测试见 `docs/TESTING.md`。没有样本、API Key或Docker运行时的项目项必须如实标记，不能当成通过。

后续按需扩展：经样本验证的HAE格式版本注册表、任务队列批次处理、来源冲突审核、更细粒度心率趋势、多用户各自接收密钥、增量导出、独立加密备份和异地恢复演练。保持查询函数受控，不向模型开放任意SQL，不自动接入未经授权的平台。
