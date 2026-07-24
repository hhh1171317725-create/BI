# 营销日报分析（Node.js）

访问页面后，粘贴当前的报表 `x-token` 并点击“加载全量数据”。Token 仅随本次请求发送给报表接口：浏览器不会保存，服务端不会写入文件，也不会存进仓库。

- `/`：大航海日报，提供优化师、项目、日期、任务四个维度的汇总。利润口径为“预估佣金 - 现金消耗”。
- `/jd`：京东 CPA 日报，提供优化师、日期、媒体、媒体账户和推客维度的汇总，以及优化师－日期下钻。京东预估/实际利润和 ROI 均包含“条件内预估赔付金额（当日）”。

两份报表使用独立的本地缓存，均支持日期筛选、分页和按日消耗折线图。

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
