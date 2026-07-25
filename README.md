# 营销日报分析（Node.js）

访问页面后，粘贴当前的报表 `x-token` 并点击“加载全量数据”。成功更新后，Token 会保存在不纳入 Git 的服务器 `.runtime/scheduler-credentials.json`（Linux 权限为 `600`）以及当前浏览器的本地存储中。点击大航海的“海”或京东的“京”可以复制，两个页面共用同一份本地 Token。

- `/`：大航海日报，提供优化师、项目、日期、任务四个维度的汇总。利润口径为“预估佣金 - 现金消耗”。首页会按北京时间检查前一天的账户＋任务数据，并在结算数比注册数低 10% 以上，或账户消耗达到 100 元但无注册时显示异常预警。预警下拉框包含前一天日报中的全部优化师及其任务，并提供“全部优化师”和“全部任务”选项；明细默认收起，闲鱼相关账户和任务不参与预警，其他预警支持在当前浏览器取消和恢复。
- `/jd`：京东 CPA 日报，提供优化师、日期、媒体、媒体账户和推客维度的汇总，以及优化师－日期下钻。京东预估/实际利润和 ROI 均包含“条件内预估赔付金额（当日）”，“有效订单数”口径为首购有效订单数与回流有效订单数之和。日期筛选区默认排除未知优化师，也可切换为保留全部数据；该条件会作用于汇总卡片、全部分析维度、趋势图和下钻数据。

两份报表使用独立的本地缓存，均支持日期筛选、分页和按日消耗折线图。

服务进程会按北京时间每天 09:00 自动拉取大航海和京东全量数据，并原子更新两份缓存。首次部署或 Token 失效后，在任一报表页面手动成功更新一次即可刷新定时任务凭据；自动更新失败不会覆盖已有缓存。

## 登录

整个站点及数据接口均需要登录，默认用户名为 `hhh`、密码为 `123456`。登录状态通过签名的 HttpOnly Cookie 保存 7 天，可从报表页右上角主动退出。生产环境可使用 `REPORT_USERNAME`、`REPORT_PASSWORD` 和 `REPORT_SESSION_SECRET` 环境变量覆盖默认值；设置固定的随机 `REPORT_SESSION_SECRET` 后，服务重启不会使现有登录状态失效。

## 数据分析宠物

两个报表右下角都有“数数鲸”悬浮宠物。未配置 AI 时，它可在本地回答消耗、利润、ROI、有效订单、优化师排名和异常预警等问题。配置 DeepSeek 或 OpenAI 后可进行更自然的多轮对话，报表上下文会由服务端发送给模型。API Key 可配置在服务器环境变量中，也可只保存在当前浏览器。

AI 提问时会读取当前日期筛选范围内的服务器底表，并根据问题中的优化师、项目、任务、媒体、账户或推客名称筛选相关明细，同时附带各维度汇总。没有明确维度时仅提供高消耗的部分明细；如明细被截断，AI 会在回答中说明，以避免模型上下文超限。

最快的配置方式是打开宠物，点击右上角齿轮，选择提供商并粘贴 API Key。该 Key 只保存在当前浏览器的本地存储中，每次提问时经本站后端转发给对应服务，不写入服务器文件或 Git。

初音未来风格的数据宠物支持拖动：关闭对话时拖动角色按钮，打开对话后拖动顶部标题栏。拖动位置会保存在当前浏览器，并自动限制在可视区域内。

在服务器创建不纳入 Git 的 `/www/wwwroot/BI/.runtime/ai.env`：

```env
AI_PROVIDER=deepseek
DEEPSEEK_API_KEY=你的_DeepSeek_API_Key
DEEPSEEK_MODEL=deepseek-v4-flash
```

随后执行 `systemctl restart dahanghai-analysis`。如使用 OpenAI，将提供商改为 `openai`，并配置 `OPENAI_API_KEY` 和可选的 `OPENAI_MODEL`。

## 宝塔部署

1. 将仓库拉取到 `/www/wwwroot/BI`。
2. 将 `deploy/dahanghai-analysis.service` 复制到 `/etc/systemd/system/`，执行 `systemctl daemon-reload && systemctl enable --now dahanghai-analysis`。
3. 在宝塔网站配置中，将 `www.huanghaha.fun` 反向代理到 `http://127.0.0.1:8765`，并配置 SSL。

服务器需安装 Node.js 18 或更高版本。每次更新代码后执行：

```bash
cd /www/wwwroot/BI
git pull origin main
systemctl restart dahanghai-analysis
```

## MySQL 数据库

项目已提供 [`database/schema.sql`](database/schema.sql) 初始化脚本。先创建并选中目标数据库，再导入脚本，即可建立大航海与京东底表、同步日志和京东指标视图。数据库结构准备完成，但当前报表仍使用 JSON 缓存；配置 MySQL 连接后再启用数据库写入和读取，避免在没有可用数据库时影响线上报表。
