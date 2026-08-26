# 前端

这里是独立部署的静态前端，不参与 Maven 构建，也不会进入后端 JAR。

- `index.html`：大航海日报
- `jd.html`：京东广义新日报
- `jd-low-activity.html`：京东低活任务报表
- `tools.html`：报表工具中心
- `mail-dingtalk.html`：QQ 邮箱未读邮件转发到钉钉群机器人
- `login.html`：登录页
- `pet.js`、`pet.css`：数据助手
- `echarts.min.js`：图表依赖
- `assets/`：图片资源

页面只通过同源的 `/api/*` 请求后端 JSON。生产环境由 Nginx 直接返回本目录中的
HTML、JavaScript、CSS 和图片，并把 `/api/` 反向代理到
`http://127.0.0.1:8765`。
