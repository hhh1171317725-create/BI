# 营销日报分析（前后端分离）

项目已拆分为两个独立部署单元：

- `frontend/` 是纯静态前端，由 Nginx 直接托管；
- Spring Boot 是纯 JSON 后端，只处理 `/api/*`，不再返回 HTML、JS、CSS、图片或页面跳转。

前端继续使用 `/`、`/jd`、`/tools`、`/chat`、`/deeplink` 和 `/login` 地址，并通过同源 `/api/*` 调用后端，因此页面功能和
用户访问地址保持不变。未登录时后端返回 JSON `401`，由前端跳转到登录页；后端异常也统一
返回 JSON，避免前端收到 HTML 错误页。

访问页面后，粘贴当前的报表 `x-token` 并点击“加载全量数据”。成功更新后，Token 会保存在不纳入 Git 的服务器 `.runtime/scheduler-credentials.json`（Linux 权限为 `600`）以及当前浏览器的本地存储中。点击大航海的“海”或京东的“京”可以复制，两个页面共用同一份本地 Token。

- `/`：大航海日报，提供优化师、项目、日期、任务四个维度的汇总。优化师、项目和任务名称均可点击，点击后自动展示对应对象的日期维度明细、每日消耗与现金 ROI 折线图。利润口径为“预估佣金 - 现金消耗”。首页会按北京时间检查前一天的账户＋任务数据，并在结算数比注册数低 10% 以上，或账户消耗达到 100 元但无注册时显示异常预警。预警可按优化师、项目和任务联动筛选，三个下拉框均提供“全部”选项；明细默认收起，闲鱼相关账户和任务不参与预警，其他预警支持在当前浏览器取消和恢复。
- `/jd`：京东 CPA 日报，提供优化师、日期、媒体、媒体账户和推客维度的汇总。媒体名称、媒体账户 ID 和推客用户名均可点击，点击后可查看对应对象的日期维度明细，以及每日消耗、预估 ROI、实际 ROI 折线图；优化师维度也保留相同的日期下钻能力。京东预估/实际利润和 ROI 均包含“条件内预估赔付金额（当日）”，“有效订单数”口径为首购有效订单数与回流有效订单数之和，“有效首购率”口径为首购有效订单数÷有效订单数。日期筛选区默认排除未知优化师，也可切换为保留全部数据；该条件会作用于汇总卡片、全部分析维度、趋势图和下钻数据。
- `/tools`：工具中心，用于添加、打开和管理当前浏览器中的常用工具链接，并提供聊天室入口。
- `/chat`：已登录设备之间共享文字、图片和文件的公共聊天室；单个文件上限 50MB。
- `/deeplink`：京东深链生成工具。按 SKU 或商品名搜索并选择底表商品，服务端自动读取对应 H5 链接，再按 `.runtime/deeplink.env` 中的默认渠道参数请求上游接口。

两份报表均从 MySQL 底表实时查询，支持日期筛选、分页和按日消耗折线图。
报表查询会把用户选择的开始、结束日期直接作为 MySQL `business_date` 条件，只读取该日期
范围内的底表记录，不再先读取整张表后由 Java 过滤；大航海预警会额外读取北京时间昨天的
少量记录。数据助手同样只读取当前页面选择的日期范围。
大航海和京东筛选区均支持输入完整账户 ID 搜索：大航海匹配账户明细 JSON 中的账户 ID，
京东匹配媒体账户 ID。账户条件在 MySQL 查询阶段生效，并同步影响汇总、表格、图表、下钻
和数据助手上下文；清空搜索框并重新应用筛选即可恢复全部账户。

服务进程会按北京时间每天 09:00 自动拉取大航海和京东全量数据，并在同一数据库事务中更新两张底表。首次部署或 Token 失效后，在任一报表页面手动成功更新一次即可刷新定时任务凭据；自动更新失败会回滚事务，不会覆盖已有数据。

后端使用 Java 21 + Spring Boot，原有 `/api/*` 接口地址和返回字段保持不变。页面资源位于
`frontend/`，不会打入可执行 JAR。

## 登录

整个站点及数据接口均需要登录，默认用户名为 `hhh`、密码为 `123456`。登录状态通过长期保存的签名 HttpOnly Cookie 维持，直到用户主动退出、清除浏览器数据、修改登录账号或会话密钥。生产环境可使用 `REPORT_USERNAME`、`REPORT_PASSWORD` 和 `REPORT_SESSION_SECRET` 环境变量覆盖默认值；设置固定的随机 `REPORT_SESSION_SECRET` 后，服务重启不会使现有登录状态失效。

## 数据分析宠物

两个报表右下角都有“数数鲸”悬浮宠物。未配置 AI 时，它可在本地回答消耗、利润、ROI、有效订单、优化师排名和异常预警等问题。配置 DeepSeek 或 OpenAI 后可进行更自然的多轮对话，报表上下文会由服务端发送给模型。API Key 可配置在服务器环境变量中，也可只保存在当前浏览器。

AI 提问时会读取当前日期筛选范围内的服务器底表，并根据问题中的优化师、项目、任务、媒体、账户或推客名称筛选相关明细，同时附带各维度汇总。没有明确维度时仅提供高消耗的部分明细；如明细被截断，AI 会在回答中说明，以避免模型上下文超限。

最快的配置方式是打开宠物，点击右上角齿轮，选择提供商并粘贴 API Key。该 Key 只保存在当前浏览器的本地存储中，每次提问时经本站后端转发给对应服务，不写入服务器文件或 Git。

人物风格的 AI 数据助手支持眨眼、转头和挥手分帧动画，也支持拖动：关闭对话时拖动角色按钮，打开对话后拖动顶部标题栏。拖动位置会保存在当前浏览器，并自动限制在可视区域内。

在服务器创建不纳入 Git 的 `/www/wwwroot/BI/.runtime/ai.env`：

```env
AI_PROVIDER=deepseek
DEEPSEEK_API_KEY=你的_DeepSeek_API_Key
DEEPSEEK_MODEL=deepseek-v4-flash
```

随后执行 `systemctl restart dahanghai-analysis`。如使用 OpenAI，将提供商改为 `openai`，并配置 `OPENAI_API_KEY` 和可选的 `OPENAI_MODEL`。

## 京东深链工具

将 `deploy/deeplink.env.example` 复制为服务器运行目录中的 `.runtime/deeplink.env`，填写当前有效的
`XZ_DEEPLINK_TOKEN` 和 `XZ_DEEPLINK_SIGN`。`X-Request-Timestamp` 由服务端在每次请求时自动生成。其余渠道、平台、账户、PID 与来源参数
已经按当前默认值写入模板。也可直接在 `/deeplink` 页面的“接口凭据设置”中粘贴并保存 token 与签名，页面不会回显完整凭据；该文件被 Git 忽略。

```bash
cp deploy/deeplink.env.example .runtime/deeplink.env
chmod 600 .runtime/deeplink.env
systemctl restart dahanghai-analysis
```

打开 `/deeplink` 后搜索并选择 SKU，再点击生成。批量 SKU 支持粘贴换行、空格或逗号分隔的多个 SKU，下载的 `.xlsx` 仅填写“直达链接名称”“DeepLink”“ULink”三列：名称为 SKU 加商品名的前 30 个字，DeepLink 与 ULink 分别取上游响应的 `deeplink_cvt` 和 `universal_link`。SKU-H5 映射来自随程序发布的 `jd-deeplink-products.json`；上游授权或签名失效时，替换该私有配置中的对应值后重启服务。

## 宝塔部署

1. 将仓库拉取到 `/www/wwwroot/BI`。
2. 将 `deploy/dahanghai-analysis.service` 复制到 `/etc/systemd/system/`，执行 `systemctl daemon-reload && systemctl enable --now dahanghai-analysis`。
3. 全量 CSV 较大时，将 `deploy/dahanghai-analysis-memory.conf` 复制到
   `/etc/systemd/system/dahanghai-analysis.service.d/memory.conf`，让 Java 最多使用服务器
   50% 内存；复制后执行 `systemctl daemon-reload && systemctl restart dahanghai-analysis`。
3. 在宝塔网站配置中将网站根目录设为 `/www/wwwroot/BI/frontend`。
4. 不要把整个网站代理到 Java。仅将 `/api/` 反向代理到
   `http://127.0.0.1:8765`，并为 `/login`、`/jd`、`/tools`、`/chat`、`/deeplink` 配置静态页面映射。可参考
   `deploy/nginx-huanghaha.fun.conf` 中的 `location` 配置；已有 SSL 配置应保留。

服务器需安装 Java 21。项目提供 Maven Wrapper，并已配置国内 Maven 下载与依赖镜像，无需单独安装 Maven。首次部署及每次更新代码后执行：

```bash
cd /www/wwwroot/BI
git pull origin main
chmod +x mvnw
./mvnw clean package -DskipTests
systemctl restart dahanghai-analysis
```

构建产物为 `target/marketing-reports-1.0.0.jar`。也可以在宝塔“Java 项目”中直接选择该 JAR，端口设为 `8765`，启动命令使用：

```bash
java -jar /www/wwwroot/BI/target/marketing-reports-1.0.0.jar
```

## MySQL 数据库

项目已提供 [`database/schema.sql`](database/schema.sql) 初始化脚本。先创建并选中目标数据库，再导入脚本，即可建立大航海与京东底表、同步日志和京东指标视图。全量更新写入 MySQL，报表查询、异常预警和 AI 底表分析均直接读取 MySQL，不再依赖 JSON 报表缓存。

在服务器创建不纳入 Git 的 `/www/wwwroot/BI/.runtime/mysql.env`：

```env
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=BI
MYSQL_USER=BI
MYSQL_PASSWORD=你的数据库强密码
MYSQL_CONNECTION_LIMIT=5
```

可复制 [`database/mysql.env.example`](database/mysql.env.example) 后修改。更新 systemd 服务文件并执行 `systemctl daemon-reload && systemctl restart dahanghai-analysis`。

程序启动时会主动读取 `.runtime/mysql.env` 和 `.runtime/ai.env`，因此使用宝塔 Java 项目或直接运行 JAR 时无需手动 `source`。密码包含 `#`、空格或 `=` 时请使用双引号包裹。

## 报表列与排序

大航海、京东的所有汇总表和日期明细表均支持自定义显示列。点击表格上方的“选择列”勾选指标，点击任意表头可在升序和降序之间切换；排序会先作用于全部查询结果，再进行分页。各报表、各维度的选择与排序设置会分别保存在当前浏览器中，可随时点击“恢复默认列”重置。

## 接口回归

纯本地测试不访问生产接口，也不需要 token：

```bash
./mvnw test
```

真实接口检查脚本不会保存或打印 token。只在服务器当前终端会话设置环境变量后运行：

```bash
export REPORT_X_TOKEN='当前有效的 x-token'
export REPORT_USER_ID='20'
APP_BASE_URL=http://127.0.0.1:8765 ./scripts/test-all-apis.sh
```

默认检查两个 RockOrca CSV 导出接口，以及登录、查询、分析、AI 本地分析和退出接口。
如需同时验证两个会全量覆盖数据库的更新接口，请明确追加
`--include-load`；运行前应确认数据库已有备份。Windows 也可运行等价的
`scripts/Test-AllApis.ps1`，并使用 `-IncludeLoadEndpoints` 开启更新接口。
