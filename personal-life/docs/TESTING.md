# 测试与发布验收

测试fixture全为synthetic；不自动导入生产库。集成脚本新建随机端口、随机密码、独立`.test-runtime/pg-*`数据库，结束后关闭，不访问`.env`中的生产库。保留目录仅供调试，均被Git忽略。不要把测试脚本改为指向个人健康数据库。

```sh
npm ci
npm run db:generate
npm run typecheck
npm test
npm run build
npm run test:integration
```

PostgreSQL集成测试包括：实际执行migration、未认证请求、错误密码、CSRF、独立接收Key、原始隔离、空数据、格式失败、重复/并发上传、UTC、跨午夜睡眠、次日00:00步数、来源选择、历史补传、日汇总刷新、AI授权、查询最小化、缺少Key错误、历史隔离、流式导出、本人删除、其他用户不受影响、会话撤销及唯一约束。

Windows管理员环境用PostgreSQL官方`pg_ctl`启动限制权限的子进程；Linux需普通非root用户运行测试。生产容器不包含测试数据库二进制。

### 真实生产构建HTTP与浏览器

先构建，再启动测试脚本的HTTP模式（它会用自己的隔离数据库启动生产Next进程，结束后关闭）：

```sh
TEST_HTTP=true npm run test:integration
```

如本机已有Playwright和Chrome：

```sh
TEST_HTTP=true TEST_BROWSER=true PLAYWRIGHT_MODULE=playwright PLAYWRIGHT_CHANNEL=chrome npm run test:integration
```

Windows PowerShell使用 `$env:TEST_HTTP='true'` 等设置方式。如果Playwright安装在其他目录，可将 `PLAYWRIGHT_MODULE` 设为该包index.mjs的file URL。测试电脑1440px、手机390px，检查所有页面、登录跳转、运行时异常与横向溢出；截图写入`.test-runtime`。

### Docker验收（必须在装有Docker的机器执行）

```sh
docker version
docker compose version
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl -f http://127.0.0.1:3100/api/healthz
docker compose logs --tail=100 web
docker compose restart web
docker compose ps
```

需要先按README填写强密码和HTTPS域名。检查重启后数据仍存在、数据库无公网端口、web仅绑定回环、普通未认证摘要401、HTTPS登录可用、手机未知JSON202且不产生假指标。`config --quiet`不会将已展开密钥打印到终端。

### 必须另外验收的项

- Linux Docker镜像构建和Compose容器实际启动（不能用本地Next构建代替）。
- 真实证书和Nginx转发，以及宝塔的实际重载方式。
- Health Auto Export真实脱敏格式映射及设备端自动化重试能力。
- 已授权真实OpenAI Key、模型可用性及服务器网络。单元测试通过mock transport调用**官方SDK**，不产生付费请求、不发送真实健康数据。
- 备份恢复演练；导出文件和离线备份分别保护，删除数据不会删除已有离线备份。

实际执行结果记录于 `TEST_RESULTS.md`；未执行项必须保持「未验证」，不能写成全部通过。
