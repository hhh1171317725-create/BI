# 开发阶段报告

| 阶段     | 已完成内容 / 修改文件                                                                                    | 验证                                                 | 未解决或依赖项                                         |
| -------- | -------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | ------------------------------------------------------ |
| Phase 1  | 检查并保留现有Java BI；新增personal-life独立Next.js/strict TS；frontend/tools.html、life-health.html入口 | TypeScript、Next生产构建、原工具中心UI回归           | 工具需要填写实际部署的HTTPS域名                        |
| Phase 2  | prisma/schema.prisma、初始migration、PostgreSQL模型、索引/唯一约束                                       | 隔离真实PostgreSQL执行迁移、约束检查                 | Linux容器里的PostgreSQL仍需Docker验收                  |
| Phase 3  | /api/health/ingest、独立密钥、流式体积限制、原始JSON隔离                                                 | 认证、媒体类型、空/错误/过大JSON测试                 | 缺少真实HAE脱敏样本，不能完成源格式映射                |
| Phase 4  | domain/ingest/analytics；UTC指纹、并发事务、日汇总、来源优先级、历史补传、失败重处理                     | 重复/并发上传、跨日、多来源、设置后重算测试          | 已验证的是内部契约；默认原始批次awaiting_mapping       |
| Phase 5  | 中文深色Dashboard、10项每日卡片、真实空状态、来源与同步时间                                              | 生产HTTP、电脑/手机浏览器、查询范围检查              | 生产数据需验证真实适配器后才出现                       |
| Phase 6  | /sleep、/fitness；规律性/标准差、工作日对比、睡眠阶段、步数/运动/体重/心率趋势、周活动                   | 分析单元测试、跨午夜阶段、移动端无横向溢出           | 可穿戴估算不作为医学诊断；冲突算法是保守规则           |
| Phase 7  | official OpenAI SDK Responses API；受控范围/指标、最小摘要、双重授权、私有历史                           | 官方SDK mock transport、受控查询、未授权/缺少Key测试 | 真实Key、模型和服务器网络尚未端到端验证                |
| Phase 8  | 独立登录、scrypt N=2^17、会话哈希、HTTPS/同源、数据库限流、流式导出、范围删除                            | 未认证/错误密码/CSRF/数据隔离/会话撤销测试           | 宿主机与备份加密由部署管理员配置                       |
| Phase 9  | Dockerfile、Compose、非root web、私有PG网络、回环端口、健康检查、启动迁移、Nginx、备份                   | 文件审查、部署流程文档                               | 当前电脑无Docker运行时；未实际验证Docker启动/HTTPS证书 |
| Phase 10 | tests、integration脚本、README、API与HAE教程、TEST_RESULTS                                               | 见TEST_RESULTS实际结果                               | 真实HAE、付费OpenAI调用、Linux Docker验证明确保留      |

实现使用了OpenAI Docs技能核对官方SDK/Responses用法：Key仅在服务端、模型环境变量配置、响应store:false。没有调用不明代理，也没有把真实健康样本发送到第三方。
