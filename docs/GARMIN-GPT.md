# 用自己的 GPT 读取佳明健康数据

部署最新代码后，进入「工具中心 → 佳明健康 → GPT 数据接口」。先连接并同步自己的佳明账户，再生成 7、30 或 90 天有效的访问密钥。新密钥只显示一次；服务器仅保存 SHA-256 摘要，页面不写入本地存储。重新生成、撤销、到期或成功更换佳明账户后，旧密钥立即失效。停用网站账户或关闭佳明工具权限也会拒绝读取。

## 配置私有 GPT

1. 打开 GPT 编辑器的「配置 → Actions → 创建新操作」。
2. 从 URL 导入 `https://www.huanghaha.fun/garmin-openapi.json`。
3. 身份验证选择 **API Key → Bearer**，仅填入密钥本身，不加 `Bearer ` 前缀。
4. 保存为仅自己使用的 GPT。不要把密钥贴入聊天内容、公开 GPT、分享链接或 URL 参数。
5. 可询问「分析最近 7 天的睡眠、运动与恢复情况，说明缺失数据」。首次使用时确认 GPT 的操作授权。

普通 ChatGPT 网页浏览不具备你的本站登录会话，也不会自动替你调用带密钥的接口。该接口用于 GPT Actions 或自己的 HTTP 客户端。GPT Actions 要求公开 HTTPS 和有效证书；如果请求被防火墙拦截，应查看 Nginx/防火墙日志核对请求来源，不能通过取消健康数据鉴权解决。

建议在 GPT 指令中加入：

> 先读取 getGarminCacheStatus 获取北京时间和最近同步时间，再读取所需日期。缺失指标和缺失日期不能当作零。每日详情使用具体 category；运动 ID 从 listGarminCachedActivities 获取，分页直到 nextOffset 为 null。responseLimited=true 表示省略或截断，不能视为完整采样。所有读取仅代表网站已同步的缓存，不代表佳明实时数据；缺少详情时提醒我先在网站补采。只作健康记录整理与趋势说明。

## 接口

所有请求使用 `Authorization: Bearer <访问密钥>`，不接受 URL 密钥或仅网站 Cookie。响应均为 `Cache-Control: no-store, private`。访问范围固定为密钥绑定的网站账户及其当前佳明缓存。

| GET 路径 | 参数 | 内容 |
| --- | --- | --- |
| `/api/garmin-external/status` | 无 | 缓存同步时间、任务状态、北京时间 |
| `/api/garmin-external/data` | `startDate`、`endDate` | 每日汇总；含首尾最多 31 天，默认最近 7 天 |
| `/api/garmin-external/day` | `date`、`category=all` | 每日详情；可指定 `sleep`、`hrv`、`spo2` 等分类 |
| `/api/garmin-external/activities` | `date`、`offset=0`、`limit=20` | 运动列表，limit 最多 50；按 nextOffset 翻页 |
| `/api/garmin-external/activity` | `date`、`activityId`、`category=all`、`includeSamples=false` | 已缓存的运动详情，默认省略采样 |

日期为 `YYYY-MM-DD`，按 `Asia/Shanghai`。步数为步；距离为米；时长为秒；热量为千卡；心率为次/分；HRV 为毫秒；体重为千克。原始详情保留佳明字段与单位。缺失指标为 null，未采集的分类不出现；详情保留采集时间、来源状态与原始 truncated 标记。单次详情数组最多 30 项、文本最多 300 字符，并限制总字段数与响应长度；出现 `responseLimited=true` 时先按具体分类查询，完整记录可在本站查看或导出。

读取不会触发佳明登录或同步。建议先在网站开启自动同步。断开佳明登录后保留的缓存仍可授权读取；如需停止 GPT 访问，请撤销密钥。

每个账户每分钟最多 60 次读取；429 带 `Retry-After: 60`。其他状态：400 参数错误，401 密钥无效/过期，403 账户或工具权限关闭，404 缓存不存在，500 缓存暂时无法读取。密钥仅支持这五个 GET 路径，不能修改网站数据或连接佳明。

## 本地测试

使用虚构健康数据的服务及 UI 测试覆盖密钥持久化、过期、撤销、轮换、跨账户隔离、分页、响应截断和浏览器密钥清理，不需要真实佳明登录信息。

## OpenAI 文档

- [GPT Actions 身份验证](https://developers.openai.com/api/docs/actions/authentication)
- [GPT Actions 上线要求](https://developers.openai.com/api/docs/actions/production)
