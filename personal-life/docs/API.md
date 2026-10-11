# API 接口契约

基础地址为 `.env` 中的 `APP_ORIGIN`。生产仅HTTPS，响应带 `Cache-Control: no-store`。所有健康读取、设置、导出、删除、对话历史都需要独立登录Cookie，不能使用BI登录Cookie替代。

| 方法      | 路径                      | 认证 / 内容                                                       |
| --------- | ------------------------- | ----------------------------------------------------------------- |
| GET       | `/api/healthz`            | 唯一公开健康检查，只返回 `{status:"ok"}`，数据库失败为503         |
| POST      | `/api/auth/login`         | JSON username/password；必须同源；返回HttpOnly随机会话Cookie      |
| POST      | `/api/auth/logout`        | 登录+同源；撤销当前会话                                           |
| POST      | `/api/health/ingest`      | 独立Bearer或X-API-Key；不接受登录Cookie代替                       |
| GET       | `/api/health/summary`     | 登录；日期范围、指标/来源、分析、同步状态、设置                   |
| GET       | `/api/health/sleep`       | 登录；睡眠区间、阶段、日记录、规律性                              |
| GET       | `/api/health/activity`    | 登录；日步数/活动能量/运动时长                                    |
| GET       | `/api/health/workouts`    | 登录；去重后的运动记录                                            |
| GET       | `/api/health/weight`      | 登录；每日优先来源最新体重                                        |
| GET       | `/api/health/sync-status` | 登录；上传时间与测量时间、成功/失败、最近50条日志和批次           |
| POST      | `/api/health/reprocess`   | 登录+同源；`{importId}`，只能处理自己的批次                       |
| GET / PUT | `/api/settings`           | 登录；PUT同源，完整设置契约，Zod校验                              |
| POST      | `/api/assistant/chat`     | 登录+同源+两级授权；`{message,consent:true}`                      |
| GET       | `/api/assistant/history`  | 登录；仅本人最近50条对话，正序                                    |
| GET       | `/api/export`             | 登录；下载流式JSON，不含认证密钥                                  |
| DELETE    | `/api/my-data`            | 登录+同源+密码；`{password,confirmation:"DELETE_MY_HEALTH_DATA"}` |

日期查询参数：`start=YYYY-MM-DD&end=YYYY-MM-DD`，默认最近30天，含首尾日期，最大367天。前端默认上海时区。AI单次最大31天。

## 错误格式

```json
{ "error": { "code": "AUTH", "message": "请先登录", "requestId": "随机追踪ID" } }
```

400格式/日期错误，401未认证，403权限/来源/授权/密码错误，404不存在，413过大，415非JSON，422规范化失败或适配器未启用，429限流，502AI上游失败，503配置/数据库不可用。不会把上游响应、数据库栈或完整请求写入公开错误。

## 健康接收

请求 `Content-Type: application/json`。Header二选一：

```text
Authorization: Bearer <你的独立接收密钥>
X-API-Key: <你的独立接收密钥>
```

默认最多5MB，嵌套最大25层。接受未知结构的**非空对象或非空数组**，保留必要原始JSON并按canonical hash去重；不把未知字段解释成健康指标。

默认成功响应：

```json
{
  "importId": "批次ID",
  "status": "awaiting_mapping",
  "receivedCount": null,
  "insertedCount": 0,
  "duplicateCount": 0,
  "message": "已安全保存原始批次，待脱敏真实样本确认映射后处理"
}
```

HTTP202代表持久保存待处理，不代表已有睡眠/步数等指标。重复上传不会产生第二份原始批次，但有新的上传日志。认证失败不会存储内容；已认证的格式/大小失败仅记录安全错误码。客户端遇网络中断/5xx可指数退避重试原批次；4xx先修正配置。按日期拆小批次，成功批次无需重传；历史数据可与最新日期重叠上传以补缺，记录唯一约束确保幂等。

### 内部 normalized-v1（测试/已验证适配器用）

以下不是HAE格式。仅当 `ALLOW_NORMALIZED_INGEST=true` 时启用：

```json
{
  "format": "normalized-v1",
  "samples": [
    {
      "metricType": "sleep_duration",
      "value": 480,
      "unit": "min",
      "startTime": "2026-10-10T23:30:00+08:00",
      "endTime": "2026-10-11T07:30:00+08:00",
      "source": "Synthetic test",
      "sourceRecordId": "mock-sleep-1"
    }
  ],
  "workouts": []
}
```

最多2000样本和500运动记录，不能两者都为空。不识别的字段拒绝；同一批规范化失败会保存原始内容并记录failed，但不部分写入指标。

指标/单位：sleep_duration→min，sleep_stage→min（额外stage），heart_rate/resting_heart_rate→bpm，steps→count，active_energy→kcal，weight→kg。可选 `sourceRecordId`、`deviceId`。阶段枚举awake/core/deep/rem/asleep/in_bed，保持源语义，不从阶段相加推断评分。

运动记录必填：workoutType、startTime、endTime、durationMinutes、source；可选calories、deviceId、sourceRecordId。时长不能超过记录区间，值不可负数。生产解析器需要根据真实文档/样本处理源单位转换，未确认单位必须进入待映射/失败状态。

## AI 查询

先在设置启用aiConsent；每次提问还需consent=true。服务端按关键词和日期选择受控函数（睡眠、运动、心率、体重），查询当前用户，最多31天，将摘要与问题送OpenAI。没有任意SQL或任意用户ID参数。历史聊天不自动发给模型。缺少Key/模型返回503，不生成虚构回复。输出是纯文本React渲染，不允许模型HTML注入。

## 限流

数据库持久化计数，所有实例共享；接收60次/分钟，认证读取180次/分钟，登录用户名8次/15分钟且全局30次/分钟，AI5次/分钟，导出2次/小时。按固定窗口计数，API权限先于用户读取；过期桶定期清理。生产可叠加Nginx限流，不能将未可信的客户端IP当作安全身份。
