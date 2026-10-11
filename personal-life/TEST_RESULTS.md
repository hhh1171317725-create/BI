# 实际测试结果 — 2026-10-11

环境：Windows、本地Node.js 24.13、Next.js 16.4、Prisma 6.19.3、隔离PostgreSQL 18.4、Chrome/Playwright。全部健康值均为synthetic测试数据，生产数据库没有导入示例数据。

| 检查                                           | 实际结果                                                         |
| ---------------------------------------------- | ---------------------------------------------------------------- |
| TypeScript strict `npm run typecheck`          | 通过                                                             |
| `npm run build`，standalone打包                | 通过                                                             |
| `npm test`                                     | 13通过，0失败                                                    |
| 实际Prisma migration deploy                    | 在隔离PostgreSQL通过                                             |
| 独立密钥、Bearer/X-API-Key、登录、CSRF         | 通过                                                             |
| 空/错误JSON、体积限制、未知结构隔离            | 通过                                                             |
| 重复/并发上传、数据库唯一约束                  | 通过                                                             |
| 跨午夜睡眠、午夜前阶段、UTC/上海时区           | 通过                                                             |
| 多来源步数不累加、历史补传、日汇总重算         | 通过                                                             |
| 七天卡片与图表查询区间分离                     | 通过                                                             |
| 失败重处理与其他用户批次不可访问               | 通过                                                             |
| 持久化限流                                     | 通过                                                             |
| AI受控查询、双重授权、缺少配置                 | 通过                                                             |
| AI聊天完整接口、官方SDK mock、对话顺序         | 通过；没有实际付费/外部请求                                      |
| 流式个人导出、本人删除、会话撤销、其他用户隔离 | 通过                                                             |
| standalone生产HTTP、未登录跳转、六个页面       | 通过                                                             |
| 1440px电脑 / 390px手机，六页、有数据图表       | 通过；无页面运行时异常、无横向溢出，已检查截图                   |
| 原BI工具中心回归                               | `scripts/tools-ui-test.cjs`通过                                  |
| 新工具入口HTTPS地址检查与跳转                  | `scripts/life-health-ui-test.cjs`通过                            |
| 生产依赖 `npm audit --omit=dev --json`         | 0个已知漏洞                                                      |
| Docker镜像构建、Compose实际启动/重启           | **未验证：当前设备未安装Docker/Podman，也无可用WSL Linux发行版** |
| Nginx证书及云服务器公网HTTPS                   | **未验证：未连接或修改真实服务器**                               |
| Health Auto Export实际样本映射/设备自动化      | **未完成：缺少脱敏真实导出样本；默认202安全原始接收模式**        |
| 真实OpenAI Key/模型/云端网络                   | **未验证：只使用官方SDK测试传输，不发送真实健康数据**            |

测试中401/403/404/413/422/503等安全错误日志是预期拒绝案例，不表示测试失败。曾遇到Windows管理员PostgreSQL启动限制，测试脚本已使用官方pg_ctl限制权限启动；浏览器测试选择器和mock HTML编码问题已修复，最终完整测试通过。

依赖修复：Prisma config链原有deepmerge-ts和effect公告项已通过有范围的overrides更新（deepmerge-ts 8.0.2、effect 3.20.0），并重新验证Prisma生成、迁移及构建。参考 [DeepmergeTS公告](https://github.com/advisories/GHSA-ggr8-5vv4-36mx) 与 [Effect公告](https://github.com/advisories/GHSA-38f7-945m-qr2g)。这不代表扫描覆盖所有未知风险，也不是医疗/安全认证。

Linux Docker和真实设备/API验收命令见README、docs/TESTING.md。不能将本地standalone测试代替Docker实际启动，也不能将normalized-v1合成数据测试当成HAE真实格式已映射。
