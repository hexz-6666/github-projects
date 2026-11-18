# REST API 契约 v1

> **v2 增量说明（已确认）：** 自 v2 起，除 `/api/v1/health` 与 `/api/v1/model/health` 外，本文件业务端点要求会话登录；复核需要 REVIEWER/ADMIN 角色与 CSRF。字段、金额精度、201+Location、409、健康语义保持不变。认证/CSRF 与 v2 新端点见 [api-contract-v2.md](api-contract-v2.md)。

基准：`http://127.0.0.1:8080`；唯一前端入口为 Spring Boot。Vue 通过 Vite 代理访问 `/api`，不直接访问 Python。JSON UTF-8；创建/复核必须 `Content-Type: application/json`。时间 RFC 3339 UTC（如 `2026-01-03T08:00:00Z`）。**浏览器订单金额为十进制字符串，Java→Python 特征金额为 JSON 数字**；评分数字范围 `[0,1]`。v1 原本没有认证；v2 增加演示会话认证后仍只能受控本机演示，不可暴露公网。

## 1. 通用约定

### 分页

查询参数 `page` 从 0 开始，默认 0；`size` 默认 20，范围 1–100。响应：

```json
{
  "items": [],
  "page": {"number": 0, "size": 20, "totalElements": 1, "totalPages": 1}
}
```

页内容为空不是错误。`status` 等枚举不匹配返回 400。订单 `keyword` 最长 64，对订单 ID/订单号/虚构客户引用做包含匹配，不能用来查询完整邮箱。

### 错误

```json
{
  "timestamp": "2026-11-07T08:00:00Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "请求参数不符合接口约束",
  "path": "/api/v1/orders",
  "requestId": "01ExampleRequestId",
  "details": [{"field": "size", "message": "must be less than or equal to 100"}]
}
```

错误详情仅字段与原因，不回显输入值、SQL、堆栈、密钥、主机或模型响应正文。`requestId` 可由 Java 生成，日志仅记录必要元数据。

| HTTP | code | 场景 |
|---|---|---|
| 400 | `VALIDATION_ERROR` / `INVALID_PARAMETER` | JSON、字段、分页、枚举不合法 |
| 404 | `ORDER_NOT_FOUND` / `ALERT_NOT_FOUND` | 订单或告警不存在 |
| 409 | `ALERT_VERSION_CONFLICT` | 复核 `expectedVersion` 过期 |
| 409 | `ALERT_ALREADY_RESOLVED` | 已完成的告警不允许继续复核 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 写接口非 JSON |
| 500 | `INTERNAL_ERROR` | 未预期内部错误，不暴露内部详情 |
| 502 | `MODEL_UNAVAILABLE` / `MODEL_INVALID_RESPONSE` | 模型超时、不可用或响应不符合契约 |
| 503 | `SERVICE_DEGRADED` | 健康聚合检查未全部通过（健康端点的状态码；业务错误体通常不含此 code） |

## 2. 聚合健康检查

### `GET /api/v1/health`

无请求体，无敏感信息。全部依赖正常时 200；任一异常或显式替身模式时 503。

```json
{
  "status": "DEGRADED",
  "service": "fraud-api",
  "storageMode": "stub",
  "checks": {
    "database": {"status": "BYPASS", "detail": "IN_MEMORY_CONTRACT_STORE"},
    "model": {"status": "UP", "modelVersion": "synthetic-rule-v1", "modelType": "DEMO_RULE"}
  },
  "timestamp": "2026-11-07T08:00:00Z"
}
```

MySQL 模式的数据库检查为 `UP`/`DOWN`；stub 模式为 `BYPASS` 且整体始终 `DEGRADED`，不谎称数据库连接成功。模型检查包含版本/类型（可用时）。Java 进程无法提供响应才视为 `DOWN`。

## 3. 订单查询

### `GET /api/v1/orders?page=0&size=20&status=PAID&keyword=DEMO`

- `status` 可选：`CREATED`/`PAID`/`CANCELLED`，不是欺诈状态。
- `keyword` 可选，1–64 字符；空白等价未提供。
- `items[]` 按 `orderedAt` 倒序、订单 ID 升序。

```json
{
  "items": [{
    "orderId": "DEMO-O-0001",
    "orderNo": "DEMO-N-0001",
    "customerRef": "DEMO-C-0001",
    "customerEmailMasked": "demo-****@example.invalid",
    "status": "PAID",
    "amount": "1200.00",
    "currency": "CNY",
    "dataOrigin": "SYNTHETIC",
    "orderedAt": "2026-01-03T08:00:00Z"
  }],
  "page": {"number": 0, "size": 20, "totalElements": 1, "totalPages": 1}
}
```

`customerEmailMasked` 只是掩码展示，不是加密。ODS 原始载荷不对前端开放。

## 4. 异常告警查询与详情

### `GET /api/v1/alerts?page=0&size=20&alertStatus=OPEN&riskLevel=HIGH`

`alertStatus` 可选 `OPEN`/`REVIEWING`/`RESOLVED`；`riskLevel` 可选 `LOW`/`MEDIUM`/`HIGH`。按 `createdAt` 倒序、`alertId` 倒序。

```json
{
  "items": [{
    "alertId": 1,
    "orderId": "DEMO-O-0001",
    "alertStatus": "OPEN",
    "riskLevel": "HIGH",
    "score": 0.99,
    "reasonCodes": ["HIGH_AMOUNT", "REPEATED_PAYMENT_ATTEMPTS"],
    "version": 1,
    "createdAt": "2026-01-03T08:05:00Z"
  }],
  "page": {"number": 0, "size": 20, "totalElements": 1, "totalPages": 1}
}
```

### `GET /api/v1/alerts/{alertId}`

`alertId` 正整数，超出数字范围或格式错误返回 400；合法但不存在返回 404，不泄露其他记录。详情在摘要字段基础上增加：

```json
{
  "alertId": 1,
  "orderId": "DEMO-O-0001",
  "scoreId": 1,
  "alertStatus": "OPEN",
  "riskLevel": "HIGH",
  "score": 0.99,
  "modelVersion": "synthetic-rule-v1",
  "modelType": "DEMO_RULE",
  "threshold": 0.7,
  "reasonCodes": ["HIGH_AMOUNT"],
  "version": 1,
  "createdAt": "2026-01-03T08:05:00Z",
  "updatedAt": "2026-01-03T08:05:00Z",
  "order": {
    "orderId": "DEMO-O-0001",
    "orderNo": "DEMO-N-0001",
    "customerRef": "DEMO-C-0001",
    "customerEmailMasked": "demo-****@example.invalid",
    "status": "PAID",
    "amount": "1200.00",
    "currency": "CNY",
    "dataOrigin": "SYNTHETIC",
    "orderedAt": "2026-01-03T08:00:00Z"
  },
  "reviews": []
}
```

## 5. 人工复核

### `POST /api/v1/alerts/{alertId}/reviews`

```json
{
  "reviewerId": "demo-reviewer-01",
  "reviewResult": "INCONCLUSIVE",
  "comment": "虚构演示：证据不足，等待复核",
  "expectedVersion": 1
}
```

约束：`reviewerId` 去除首尾空白后 1–64 字符且不能全为空白；`reviewResult` 为 `CONFIRMED`/`REJECTED`/`INCONCLUSIVE`；`comment` 可选，最长 500，只允许虚构、非敏感文本；`expectedVersion` 为大于等于 1 的整数。空评论视为 `null`，非空评论按去除首尾空白后的内容保存。

状态迁移：`CONFIRMED` 或 `REJECTED` → `RESOLVED`；`INCONCLUSIVE` → `REVIEWING`。已 `RESOLVED` 返回 409。Java 在单个数据库事务内追加复核历史并做 `version` 条件更新，不能丢失并发更新。

成功返回 **201**，`Location: /api/v1/alerts/1`：

```json
{
  "reviewId": 1,
  "alertId": 1,
  "previousStatus": "OPEN",
  "newStatus": "REVIEWING",
  "version": 2,
  "reviewResult": "INCONCLUSIVE",
  "reviewedAt": "2026-11-07T08:01:00Z"
}
```

版本过期返回 409，客户端重新获取详情后再提交。**安全语义（v2）：** 请求体仍接受 `reviewerId` 以兼容 v1，但服务端忽略它，复核身份取会话 `Authentication`；未登录 401，角色不足 403，CSRF 缺失 403。复核结果仅表示本次人工结论，不能自动作为无偏差的真实训练标签。

## 6. 模型健康与订单评分

### Java：`GET /api/v1/model/health`

代理查询模型健康；成功返回 200：

```json
{
  "status": "UP",
  "service": "fraud-model",
  "modelVersion": "synthetic-rule-v1",
  "modelType": "DEMO_RULE",
  "threshold": 0.7,
  "labelSemantics": "RISK_SCORE_NOT_GROUND_TRUTH"
}
```

模型不可用返回 502 `MODEL_UNAVAILABLE`。Java 不缓存旧健康结果冒充当前可用。

### Java：`POST /api/v1/orders/{orderId}/score`

`orderId` 为 1–64 字符，匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`；无请求体；若带 `Content-Type` 必须为 JSON。订单必须存在且已有 DWS 特征快照，否则 404 `ORDER_NOT_FOUND`（首版缺失特征不另设业务错误）。Java 将快照传给 Python，不接收前端任意评分特征。

```json
{
  "scoreId": 2,
  "orderId": "DEMO-O-0002",
  "score": 0.05,
  "riskLevel": "LOW",
  "modelVersion": "synthetic-rule-v1",
  "modelType": "DEMO_RULE",
  "threshold": 0.7,
  "ruleCodes": [],
  "scoredAt": "2026-11-07T08:02:00Z"
}
```

同一订单重复调用会新增评分历史，不覆盖旧分数。首版**不自动创建告警**；自动告警策略、重复评分节流、异步 ETL 是后续工作。

### Python 内部接口（不直接暴露给 Vue）

- `GET /health`：健康信息同上，不含 `ready`；`GET /api/v1/model/health`：同上并带 `ready: true`。Java `/api/v1/model/health` 透传 Python `/health` 的约定字段，因此不包含 `ready`。
- `POST /internal/v1/score`：只接受 `application/json` 或兼容的 JSON 媒体类型（如 `application/*+json`），其他类型返回 422；字段之外一律拒绝。

```json
{
  "orderId": "DEMO-O-0002",
  "amount": 680.00,
  "currency": "CNY",
  "paymentCount": 1,
  "accountAgeDays": 94,
  "transactionCount24h": 1
}
```

`orderId` 1–64；`amount` 有限正数，最多 18 位（16 位整数、2 位小数），范围与 MySQL `DECIMAL(18,2)` 一致；`currency` 仅 `CNY`；计数为 JSON 整数，`paymentCount`/`transactionCount24h` 为 1–100000，`accountAgeDays` 为 0–365000。返回字段与 Java 评分结果相同（无 `scoreId`），并带 `scoredAt` 和 `labelSemantics`。`modelType=DEMO_RULE`，规则见 [data-dictionary.md](data-dictionary.md#6-演示固定规则)。

固定规则得分不是概率、诊断、真实欺诈结论或训练结果。Python 不访问数据库，不持久化输入，不记录请求正文；Java 只在模型返回合法响应后保存评分。
