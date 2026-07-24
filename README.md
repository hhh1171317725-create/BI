# 营销日报分析（Node.js）

访问页面后，粘贴当前的报表 `x-token` 并点击“加载全量数据”。Token 不会写入服务端文件或仓库；成功更新后会保存在当前浏览器的本地存储中，点击大航海的“海”或京东的“京”可以复制，两个页面共用同一份本地 Token。

- `/`：大航海日报，提供优化师、项目、日期、任务四个维度的汇总。利润口径为“预估佣金 - 现金消耗”。首页会按北京时间检查前一天的账户＋任务数据，并在结算数比注册数低 10% 以上，或账户消耗达到 100 元但无注册时显示异常预警。预警下拉框包含前一天日报中的全部优化师及其任务，并提供“全部优化师”和“全部任务”选项；明细默认收起，闲鱼相关账户和任务不参与预警，其他预警支持在当前浏览器取消和恢复。
- `/jd`：京东 CPA 日报，提供优化师、日期、媒体、媒体账户和推客维度的汇总，以及优化师－日期下钻。京东预估/实际利润和 ROI 均包含“条件内预估赔付金额（当日）”，“有效订单数”口径为首购有效订单数与回流有效订单数之和。日期筛选区默认排除未知优化师，也可切换为保留全部数据；该条件会作用于汇总卡片、全部分析维度、趋势图和下钻数据。

两份报表使用独立的本地缓存，均支持日期筛选、分页和按日消耗折线图。

## 数据分析宠物

两个报表右下角都有“数数鲸”悬浮宠物。未配置 AI 时，它可在本地回答消耗、利润、ROI、有效订单、优化师排名和异常预警等问题。配置 DeepSeek 或 OpenAI 后可进行更自然的多轮对话，报表上下文会由服务端发送给模型。API Key 可配置在服务器环境变量中，也可只保存在当前浏览器。

最快的配置方式是打开宠物，点击右上角齿轮，选择提供商并粘贴 API Key。该 Key 只保存在当前浏览器的本地存储中，每次提问时经本站后端转发给对应服务，不写入服务器文件或 Git。

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
