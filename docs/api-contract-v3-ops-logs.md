# v3 运行日志契约（M3）

状态：M3 增量契约。仅新增结构化运行诊断事件、ADMIN 查询 API 与客户端错误上报；不修改 v1/v2/M2 接口、V1–V4 迁移或 `audit_event`。

## 1. 边界

- 这是运行诊断日志，不是不可删除审计，也不是任意日志文件读取接口。
- 不提供文件路径参数、原始日志行、堆栈、SQL、请求正文、Cookie、密码、邮箱、卡/地址标识、模型特征值或 IEEE/ Olist 行级数据。
- 不提供前端删除服务器日志功能；仅由保留任务按 30 天/50,000 条软上限清理。
- 不接入 WebSocket、SSE、外部日志平台或通用调度平台。
- 同步导入/训练/工单操作只记录任务开始、完成、失败事件，不描述成异步调度平台。

## 2. 数据表

`database/migrations/V5__v3_ops_events.sql` 新增 `ops_log_event`：

| 字段 | 说明 |
|---|---|
| `event_id` | 自增主键，游标第二排序键 |
| `occurred_at` | UTC 事件时间 |
| `source` | `FRONTEND/BACKEND/MODEL/TASK` |
| `level` | `INFO/WARN/ERROR`；不产生 DEBUG |
| `route` | 规范化路由模板或空 |
| `code` | 固定枚举 |
| `summary` | 服务端固定摘要，不接受客户端自由文本 |
| `task_type` | 固定任务类型或空 |
| `task_id` | 批次号/模型版本/工单/配置 ID |
| `request_id` | 当前 HTTP 请求由服务端生成的 requestId |
| `related_request_id` | 客户端上报的失败请求关联 ID，不可信、只做格式校验 |
| `actor_id` | 服务端会话用户名 |
| `build_version` | 前端构建版本，最大 32 |
| `duration_ms` | 非负耗时，可空 |
| `created_at` | 写入时间 |

V5 是 additive；不修改 V1–V4、不修改 `audit_event`、不自动建表、不自动修复 schema。

## 3. 级别

顺序：

```text
DEBUG < INFO < WARN < ERROR
```

- 不产生 DEBUG，不允许查询 DEBUG。
- `GET` 默认 `minLevel=WARN`，即 WARN + ERROR。
- ADMIN 最低可查 `INFO`，即 INFO + WARN + ERROR。
- `minLevel=ERROR` 只返回 ERROR。
- `minLevel=DEBUG` 返回 400 `INVALID_PARAMETER`。

## 4. ADMIN 查询

```text
GET /api/v3/ops/logs
```

仅 `ADMIN`，需要已登录 Session。

查询参数：

| 参数 | 说明 |
|---|---|
| `source` | `FRONTEND/BACKEND/MODEL/TASK` |
| `minLevel` | `INFO/WARN/ERROR`，默认 WARN |
| `taskType` | 固定任务类型，最多 32 |
| `taskId` | 精确匹配，最多 64 |
| `requestId` | 精确匹配上报请求自身的 requestId |
| `relatedRequestId` | 精确匹配失败请求的关联 requestId |
| `from` | ISO-8601 UTC，包含 |
| `to` | ISO-8601 UTC，不包含 |
| `cursor` | 不透明游标 |
| `limit` | 默认 50，范围 1–100 |

时间范围最多 30 天；`from` 必须早于 `to`。查询按 `occurred_at DESC, event_id DESC` 排序，游标为 `(occurred_at,event_id)` 的不透明编码。

响应：

```json
{
  "items": [
    {
      "eventId": 123,
      "timestamp": "2026-11-12T08:00:00Z",
      "source": "TASK",
      "level": "INFO",
      "route": null,
      "code": "IMPORT_COMPLETED",
      "summary": "导入任务已完成",
      "taskType": "IMPORT_OLIST",
      "taskId": "IMP-...",
      "requestId": "server-request-uuid",
      "relatedRequestId": null,
      "actorId": "demo_admin",
      "buildVersion": null,
      "durationMs": 42
    }
  ],
  "nextCursor": "opaque-or-null",
  "hasMore": false,
  "minLevel": "WARN"
}
```

`requestId` 是服务端为当前请求生成的 ID；`relatedRequestId` 是客户端声称的失败请求 ID。查询时二者分别精确匹配，不混用、不模糊包含。

## 5. 客户端错误上报

```text
POST /api/v3/ops/client-errors
```

仅已登录用户，CSRF 必需；服务端固定 `source=FRONTEND`，`actor_id` 取 Session，`request_id` 取当前上报请求的服务端 ID。

请求体只允许：

```json
{
  "code": "CLIENT_HTTP_500",
  "route": "WORK_ORDERS",
  "relatedRequestId": "550e8400-e29b-41d4-a716-446655440000",
  "buildVersion": "2026.11.12"
}
```

禁止 `level`、`actorId`、`summary`、原始 URL、查询参数、堆栈、请求体、Cookie、SQL、模型特征、任意 `safeDetail` 或未知字段。

`relatedRequestId` 只校验 UUID 格式和长度，不作为鉴权、路由或权限依据。

首期只上报 API 失败，不采集 `window.onerror`、未处理 Promise、浏览器堆栈或运行时异常；401 不自动上报。

### 客户端 code 白名单

| code | 服务端 level | 固定摘要 |
|---|---|---|
| `CLIENT_HTTP_400` | WARN | 客户端请求参数无效（400） |
| `CLIENT_HTTP_403` | WARN | 客户端请求被拒绝（403） |
| `CLIENT_HTTP_404` | WARN | 客户端请求资源不存在（404） |
| `CLIENT_HTTP_409` | WARN | 客户端请求发生冲突（409） |
| `CLIENT_HTTP_422` | WARN | 客户端请求参数无效（422） |
| `CLIENT_HTTP_429` | WARN | 客户端请求被限流（429） |
| `CLIENT_HTTP_5XX` | ERROR | 客户端观察到服务端错误（5xx） |
| `CLIENT_NETWORK_ERROR` | ERROR | 客户端无法连接服务 |
| `CLIENT_RESPONSE_INVALID` | WARN | 客户端收到无法解析的响应 |

限流为每用户/会话 20 次/分钟，单实例内存限流，不是多实例共享限流；超限 429 `RATE_LIMITED`，不写入日志事件。

### route key

| route key | 规范化模板 |
|---|---|
| `ORDERS` | `/orders` |
| `ALERTS` | `/alerts` |
| `ALERT_DETAIL` | `/alerts/:id` |
| `WORK_ORDERS` | `/workorders` |
| `WORK_ORDER_DETAIL` | `/workorders/:id` |
| `WORK_ORDER_CONFIGS` | `/work-order-configs` |
| `QUALITY` | `/quality` |
| `MODELS` | `/models` |
| `HEALTH` | `/health` |
| `LOGIN` | `/login` |

不允许任意 path、query string 或 hash。

## 6. 服务端事件 code 与语义

### BACKEND

| code | level | 时点/语义 |
|---|---|---|
| `HTTP_REQUEST_FAILED` | WARN/ERROR（按状态码） | HTTP 请求结果失败；不声称数据库成功 |

### TASK

| code | level | 时点/语义 |
|---|---|---|
| `IMPORT_STARTED` | INFO | 已开始尝试 |
| `IMPORT_COMPLETED` | INFO | 导入服务正常返回且写入步骤完成；不是单事务承诺 |
| `IMPORT_FAILED` | ERROR | 导入失败/业务返回 FAILED |
| `WORK_ORDER_CREATE_STARTED` | INFO | 建单尝试开始 |
| `WORK_ORDER_CREATED` | INFO | writer 事务提交后 |
| `WORK_ORDER_CREATE_IDEMPOTENT` | INFO | 幂等返回已有工单；不表示新建 |
| `WORK_ORDER_CREATE_REJECTED` | WARN | 建单被校验/业务拒绝 |
| `WORK_ORDER_ASSIGN_STARTED` | INFO | 分派尝试 |
| `WORK_ORDER_ASSIGN_COMPLETED` | INFO | 分派事务提交后 |
| `WORK_ORDER_ASSIGN_REJECTED` | WARN | 分派被拒绝 |
| `WORK_ORDER_TRANSITION_STARTED` | INFO | 状态流转尝试 |
| `WORK_ORDER_TRANSITION_COMPLETED` | INFO | 状态流转事务提交后 |
| `WORK_ORDER_TRANSITION_REJECTED` | WARN | 状态流转被拒绝 |
| `WORK_ORDER_NOTE_STARTED` | INFO | 备注写入尝试 |
| `WORK_ORDER_NOTE_COMPLETED` | INFO | 备注事务提交后 |
| `WORK_ORDER_NOTE_REJECTED` | WARN | 备注写入被拒绝 |
| `WORK_ORDER_CONFIG_CREATE_STARTED` | INFO | 配置创建尝试 |
| `WORK_ORDER_CONFIG_CREATED` | INFO | 配置创建事务提交后 |
| `WORK_ORDER_CONFIG_CREATE_REJECTED` | WARN | 创建被拒绝 |
| `WORK_ORDER_CONFIG_UPDATE_STARTED` | INFO | 配置更新尝试 |
| `WORK_ORDER_CONFIG_UPDATED` | INFO | 配置更新事务提交后 |
| `WORK_ORDER_CONFIG_UPDATE_REJECTED` | WARN | 更新被拒绝 |
| `WORK_ORDER_CONFIG_PUBLISH_STARTED` | INFO | 发布尝试 |
| `WORK_ORDER_CONFIG_PUBLISHED` | INFO | 发布事务提交后 |
| `WORK_ORDER_CONFIG_PUBLISH_FAILED` | WARN | 发布失败 |
| `WORK_ORDER_CONFIG_DISABLE_STARTED` | INFO | 停用尝试 |
| `WORK_ORDER_CONFIG_DISABLED` | INFO | 停用事务提交后 |
| `WORK_ORDER_CONFIG_DISABLE_FAILED` | WARN | 停用失败 |
| `WORK_ORDER_CONFIG_VALIDATION_FAILED` | WARN | 配置字段/触发器校验失败 |
| `WORK_ORDER_CONFIG_VERSION_CONFLICT` | WARN | 版本冲突 |
| `WORK_ORDER_CONFIG_NOT_FOUND` | WARN | 配置不存在 |

### MODEL

| code | level | 时点/语义 |
|---|---|---|
| `MODEL_SCORE_STARTED` | INFO | Java 调模型评分开始 |
| `MODEL_SCORE_COMPLETED` | INFO | 评分调用和写入返回后 |
| `MODEL_SCORE_FAILED` | ERROR | 评分调用失败 |
| `MODEL_TRAIN_STARTED` | INFO | Java 调训练开始 |
| `MODEL_TRAIN_COMPLETED` | INFO | 训练调用正常返回且 Java 写入完成 |
| `MODEL_TRAIN_FAILED` | ERROR | 训练调用失败 |

### 事务语义

- 有 `@Transactional` 的工单/配置操作：成功事件在 service 返回后记录，此时 Spring 事务代理已提交；失败在事务回滚后记录。
- `WorkOrderCreateWriter` 内层事务返回后，工单创建成功事件才可记录。
- 导入/训练没有单一业务事务；`IMPORT_COMPLETED`/`MODEL_TRAIN_COMPLETED` 只表示服务调用正常返回且写入步骤完成，不声称“单事务已提交”。
- 业务已提交但后续 HTTP 响应失败时，业务成功事件与 `HTTP_REQUEST_FAILED` 可以同时存在，二者语义不同。
- 事务回滚不得留下成功事件；测试必须验证失败路径没有 `*_COMPLETED`。

## 7. 健康与降级

`GET /api/v3/ops/health` 仅 ADMIN。

响应示例：

```json
{
  "status": "UP",
  "processLocal": true,
  "countersResetOnRestart": true,
  "probed": true,
  "lastProbeAt": "2026-11-12T08:00:00Z",
  "lastWriteFailureAt": null,
  "writeFailureCount": 0,
  "lastPruneAt": null,
  "lastPruneFailureAt": null,
  "pruneFailureCount": 0,
  "lastPruneErrorCode": "NONE"
}
```

- 进程启动后、存储成功探测前为 `DEGRADED`，不会未经检查报告 `UP`。
- `DISABLED` 表示 `app.ops-log.enabled=false`。
- 状态是进程级，重启后计数清零；不代表集群共享健康状态。
- 日志写入失败不影响导入/训练/工单结果；失败会进入 `DEGRADED`，并以限频固定 code 记录到应用日志。兜底告警不含异常消息、堆栈、SQL 或请求内容。

## 8. 保留策略

- `ops_log_event` 全表 30 天或 50,000 条软上限，先到者为准。
- 由专用 `OpsLogRetentionJob` 定时清理，仅执行日志保留，不扩展成通用调度平台。
- 每批 1,000 条，每次最多 10 批；清理期间允许暂时超过 50,000 条。
- 清理失败被捕获并标记 `DEGRADED`，下次重试；不写回日志表，不递归。
- 不提供删除 API 或前端服务器日志删除按钮。
- Stub 在 append 时按同一 30 天/50,000 条边界裁剪。

## 9. 部署顺序

1. 先应用 V5。
2. 确认 `ops_log_event` 可访问。
3. 再启用 M3 日志写入和前端入口。

V5 不存在或日志存储不可用时，业务仍可运行；`GET /api/v3/ops/health` 返回 `DEGRADED`，前端显示“日志功能降级，不影响业务操作”，不伪造日志。

## 10. 错误码

| HTTP | code | 说明 |
|---|---|---|
| 400 | `INVALID_PARAMETER` | 非法级别、游标、时间范围、route/code/relatedRequestId |
| 401 | `UNAUTHENTICATED` | 未登录 |
| 403 | `FORBIDDEN` | 非 ADMIN 查询日志/健康 |
| 429 | `RATE_LIMITED` | 客户端错误上报超过限制 |
| 500 | `INTERNAL_ERROR` | 未预期错误；不返回堆栈/SQL |
