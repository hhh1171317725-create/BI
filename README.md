# 大航海日报分析系统（Node.js）

双击 `启动大航海分析.bat`，浏览器会打开本地面板。

1. 服务端配置好 `x-token` 后，页面会自动加载全量数据。
2. 点击“刷新全量数据”可重新读取报表；请求不会携带 `startTime` 或 `endTime`。
3. 在“优化师 / 项目 / 时间 / 任务”四个维度间切换；日期过滤只在已加载的本机数据上重新汇总。

项目口径由任务名自动识别：`淘宝闲鱼促活`、`淘宝闪购MCVR`、`淘宝促购CVR`、`淘宝促活UV`、`其他项目`。

## 宝塔部署

域名 `www.huanghaha.fun` 使用 Nginx 反向代理到本机 `127.0.0.1:8765`。

1. 将仓库拉取到 `/www/wwwroot/BI`，并执行 `mkdir -p /www/wwwroot/BI/.runtime && chown -R www:www /www/wwwroot/BI`。
2. 将 `deploy/dahanghai-analysis.service` 复制到 `/etc/systemd/system/`，执行 `systemctl daemon-reload && systemctl enable --now dahanghai-analysis`。
3. 在宝塔的网站配置中使用 `deploy/nginx-huanghaha.fun.conf` 的反向代理配置，并在 SSL 页面申请证书、强制 HTTPS。

服务器需安装 Node.js 18 或更高版本。服务运行后访问 `http://www.huanghaha.fun`；证书启用后访问 `https://www.huanghaha.fun`。

### 服务端 token 配置

私有仓库已包含 `/www/wwwroot/BI/.runtime/settings.json`，服务会自动读取其中的 token。更新 token 时直接修改该文件后提交并推送。

```json
{"token":"你的 x-token","userId":"20"}
```

该文件仅由 Node 服务读取，浏览器不会拿到 token。也可以在 systemd 服务中设置 `DHH_TOKEN` 与 `DHH_USER_ID` 环境变量。
