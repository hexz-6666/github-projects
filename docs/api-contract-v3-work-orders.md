# v3 M2 工单 API 契约（增量）

状态：M2 在 v1/v2 之上增量增加“风险告警工单、人工显式转换、配置生命周期、处理动作与备注”。v1/v2 的路径、字段和响应语义不变；M2 不改 Python、模型协议、数据源/标签语义，也不引入 MQ、BPMN、异步任务平台、日志中心、看板或图表。所有示例均为虚构数据。

## 1. 角色与可见范围

| 角色 | 配置 | 工单 |
|---|---|---|
| `ANALYST` | 只读；仅已发布快照 | 队列只读；仅未分派或本人；不能写 |
| `REVIEWER` | 只读；仅已发布快照 | 可显式建单/领取/处理本人；不能看 `scope=all`，不能分派他人/取消 |
| `ADMIN` | 创建/编辑/发布/停用/预览 | 可 `scope=all`、分派/改派、领取、处理、取消 |

- 非 ADMIN 的列表与详情只返回已发布快照；不返回 `draft`、当前草稿字段、版本号或未发布差异。
- 非 ADMIN 的关键字搜索按已发布快照的 `code/name` 匹配，不能通过草稿名称探测未发布配置。
- 工单详情对未分派工单或本人/ADMIN 可见；已分派给他人时非 ADMIN 返回 `403`。
- 角色只来自服务端 `Authentication`；请求体不接收 `operatorId`/`reviewerId` 冒充。

## 2. 工单配置

### 2.1 生命周期

`DRAFT → ACTIVE → DISABLED`，其中：

- `DRAFT`：可编辑、可发布；不可停用。
- `ACTIVE`：可编辑（形成未发布草稿）、可再次发布、可停用；匹配始终使用最后一次已发布快照。
- `DISABLED`：终态；不可更新、发布、停用或作为候选/预览。
- 每次成功写操作 `version + 1`；`update/publish/disable` 必须带 `expectedVersion`，SQL 更新同时校验版本。
- `published_version` 指向不可变的 `work_order_config_version.version_no`；已发布版本只追加，不覆盖。
- `enabled` 在响应中是派生字段：`status == ACTIVE`，不是数据库列。

### 2.2 触发白名单

`trigger` 只允许以下结构化字段，不接受表达式、脚本、SQL：

- `sourceSystems`：`SYNTHETIC`、`OLIST`，最多 2 个；
- `riskLevels`：`LOW`、`MEDIUM`、`HIGH`，最多 3 个；
- `alertStatuses`：`OPEN`、`REVIEWING`，最多 2 个；
- `reasonCodes`：`HIGH_AMOUNT`、`REPEATED_PAYMENT_ATTEMPTS`、`NEW_ACCOUNT`、`HIGH_VELOCITY`，最多 4 个。

同一字段内 OR，不同字段间 AND。字段为空表示不限制；四个字段都为空则拒绝。`reasonCodes` 与告警原因码取交集。

`riskScoreThreshold` 是顶层字段，语义固定并原样返回：

```text
风险分阈值（非欺诈概率）
```

匹配规则是 `alert.score >= riskScoreThreshold`，不是概率，也不是 `>`。阈值范围 0–1。

### 2.3 端点

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v3/work-order-configs?keyword=&status=&page=&size=` | 任意登录 | ADMIN 看当前草稿；其他角色只看已发布 |
| POST | `/api/v3/work-order-configs` | ADMIN | 创建 DRAFT；`code` 不可变 |
| GET | `/api/v3/work-order-configs/{id}` | 任意登录 | 非 ADMIN 无草稿字段 |
| PUT | `/api/v3/work-order-configs/{id}` | ADMIN | 更新；必须 `expectedVersion`；不允许改 `code` |
| POST | `/api/v3/work-order-configs/{id}/publish` | ADMIN | 发布新不可变版本；必须 `expectedVersion` |
| POST | `/api/v3/work-order-configs/{id}/disable` | ADMIN | 停用；必须 `expectedVersion`；终态 |
| GET | `/api/v3/work-order-configs/{id}/versions` | 任意登录 | 已发布版本记录；非 ADMIN 仅可见已发布配置 |
| POST | `/api/v3/work-order-configs/preview` | REVIEWER/ADMIN | 只预览最后一次已发布版本 |

创建请求示例：

```json
{
  "code": "HIGH_RISK_REVIEW",
  "name": "高风险复核",
  "description": "虚构演示配置",
  "priority": 3,
  "trigger": {
    "sourceSystems": ["SYNTHETIC"],
    "riskLevels": ["HIGH"],
    "alertStatuses": ["OPEN", "REVIEWING"],
    "reasonCodes": []
  },
  "riskScoreThreshold": 0.8000,
  "slaMinutes": 120,
  "requiresReview": true,
  "assignRole": "REVIEWER"
}
```

更新请求不包含 `code`，必须包含 `expectedVersion`。

预览请求与响应：

```json
{"configId": 1}
```

```json
{
  "configId": 1,
  "publishedVersion": 2,
  "totalMatched": 1,
  "groupsByRiskLevel": [{"key": "HIGH", "count": 1}],
  "groupsBySourceSystem": [{"key": "SYNTHETIC", "count": 1}]
}
```

预览只返回计数与分组，不返回原始告警行。`DRAFT`、没有已发布版本或 `DISABLED` 配置返回 `409 CONFIG_NOT_PUBLISHED` / `409 CONFIG_DISABLED`；不提供草稿预览端点。

## 3. 告警转工单

### 3.1 显式选择

- 只有 `OPEN`/`REVIEWING` 告警可转工单；`RESOLVED` 返回 `409 ALERT_NOT_ACTIONABLE`。
- 必须由 REVIEWER/ADMIN 显式提交 `configId`；前端不默认选中，也不自动转换。
- 配置必须 `ACTIVE`、有已发布版本且匹配当前告警，否则 `409 CONFIG_NOT_MATCHED` / `CONFIG_NOT_PUBLISHED` / `CONFIG_DISABLED`。
- 每个告警最多一个工单，由数据库 `UNIQUE(alert_id)` 保证。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v3/alerts/{alertId}/work-order-candidates` | REVIEWER/ADMIN | 返回 `actionable`、候选配置和可见的 `existingWorkOrder` |
| GET | `/api/v3/alerts/{alertId}/work-orders` | 任意登录 | 双向追踪；v1 告警详情字段不变 |
| POST | `/api/v3/alerts/{alertId}/work-orders` | REVIEWER/ADMIN | `{configId}`；新建 `201`，幂等 `200` |

幂等语义：

- 先按 `alert_id` 查已有工单，再检查告警是否可处理、配置是否 `ACTIVE`；因此同一 `configId` 已存在时，即使告警后来已解决或配置已停用，仍返回 `200` 和 `"idempotent": true` 及原工单；
- 不同 `configId` 已存在：`409 WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG`，不静默复用；
- 建单在独立事务内按 `告警 → 配置` 固定顺序锁定，检查、`work_order` 插入、`CREATED` 事件和审计同事务提交；并发唯一键冲突回滚后，在事务外读取已有工单；
- `UNIQUE(alert_id)` 仍由数据库最终兜底。

建单时把配置快照、版本、SLA、`requiresReview`、`assignRole` 固化到工单；后续配置发布或修改不影响已有工单。`dueAt = createdAt + slaMinutes`（取已发布快照的 SLA，不取未发布草稿）。

## 4. 工单处理

状态机：

```text
OPEN → ASSIGNED → IN_PROGRESS → RESOLVED
OPEN / ASSIGNED / IN_PROGRESS → CANCELLED
```

终态不可重开、不可再加备注。`CANCEL` 和 `RESOLVE` 必须有非空备注，备注最长 500。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/api/v3/work-orders?scope=mine|queue|all&status=&page=&size=` | 任意登录 | `all` 仅 ADMIN |
| GET | `/api/v3/work-orders/{id}` | 可见范围 | 详情、快照、事件、候选复核 |
| GET | `/api/v3/work-orders/assignees` | ADMIN | 只返回账号/角色，无密码 |
| POST | `/api/v3/work-orders/{id}/assign` | REVIEWER/ADMIN | `{assigneeId, expectedVersion}` |
| POST | `/api/v3/work-orders/{id}/transitions` | REVIEWER/ADMIN | `{transition, expectedVersion, note?, resolutionReviewId?}` |
| POST | `/api/v3/work-orders/{id}/notes` | 当前处理人/ADMIN | `{note, expectedVersion}`；追加 `NOTE_ADDED` |

动作为 `CLAIM`、`START`、`RESOLVE`、`CANCEL`。`CANCEL` 仅 ADMIN。ADMIN 指派到与配置 `assignRole` 不同的有效 REVIEWER/ADMIN 时，审计 `detail.roleOverride=true`；不通过请求体伪造角色。

### 4.0 服务端动作能力

详情、创建响应和动作响应都包含 `actions`。前端直接使用这些字段，不再自行推断权限：

```json
{
  "actions": {
    "canAssign": false,
    "canAssignSelf": true,
    "canClaim": true,
    "canStart": false,
    "canResolve": false,
    "canCancel": false,
    "canAddNote": false
  }
}
```

- `canAssign`：ADMIN，且 `OPEN`/`ASSIGNED`；
- `canAssignSelf`/`canClaim`：REVIEWER 且快照 `assignRole=REVIEWER` 的 `OPEN` 工单；ADMIN 使用 `canAssign` 或 `canClaim`；
- `canStart`：`ASSIGNED` 且当前用户是处理人或 ADMIN；
- `canResolve`：`IN_PROGRESS` 且当前用户是处理人或 ADMIN；
- `canCancel`：仅 ADMIN 且非终态；前端必须弹出不可恢复确认框，取消确认不得发送请求；
- `canAddNote`：非终态且当前用户是处理人或 ADMIN。

### 4.1 完成复核规则

- `requiresReview=true`：`RESOLVE` 必须带同一告警、非 `INCONCLUSIVE`（`CONFIRMED`/`REJECTED`）且 `review.createdAt >= workOrder.createdAt` 的复核记录；否则 `RESOLUTION_REVIEW_REQUIRED` / `RESOLUTION_REVIEW_NOT_FOUND` / `RESOLUTION_REVIEW_INCONCLUSIVE` / `RESOLUTION_REVIEW_PREDATES_WORK_ORDER`。
- `requiresReview=false`：可只带备注完成，也可关联一条有效复核。
- 复核本身仍走 v1 `POST /api/v1/alerts/{alertId}/reviews`，M2 不改变 v1 语义。

### 4.2 SLA

`ON_TRACK`、`OVERDUE`、`MET`、`BREACHED`、`CANCELLED`。`CANCELLED` 不是完成；`MET`/`BREACHED` 只在 `RESOLVED` 时出现。

## 5. 错误与并发

通用错误包络沿用 v2。M2 新增典型错误码：

| HTTP | code |
|---|---|
| 409 | `WORK_ORDER_CONFIG_CODE_EXISTS` |
| 409 | `WORK_ORDER_CONFIG_VERSION_CONFLICT` |
| 409 | `CONFIG_NOT_PUBLISHED`、`CONFIG_DISABLED` |
| 409 | `CONFIG_NOT_MATCHED`、`ALERT_NOT_ACTIONABLE` |
| 409 | `WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG` |
| 409 | `WORK_ORDER_TERMINAL`、`INVALID_WORK_ORDER_TRANSITION` |
| 409 | `WORK_ORDER_VERSION_CONFLICT` |
| 409 | `RESOLUTION_REVIEW_REQUIRED` 等复核错误 |
| 403 | `FORBIDDEN`（角色/可见范围） |

所有写请求需要 v2 CSRF 头。事件与审计在同一事务写入；失败回滚，不产生“有事件无审计”或反向状态。

## 6. 数据库与验证边界

V4 仅新增 `work_order_config`、`work_order_config_version`、`work_order`、`work_order_event` 及约束/索引/外键，不修改 V1/V3 表数据，不 DROP 表、不清理历史。发布顺序必须先插入不可变版本、再更新 `published_version`，以满足 MySQL 复合外键。

新增的组合完整性约束：

- `ads_alert_review` 唯一键 `uk_review_id_alert(review_id, alert_id)`，`work_order` 外键 `fk_wo_resolution_review_alert(resolution_review_id, alert_id)`，阻止引用其他告警的复核记录；
- `work_order_config_version` 唯一键 `uk_wocv_id_config_version(id, config_id, version_no)`，`work_order` 外键 `fk_wo_config_version_consistent(config_version_id, config_id, config_version_no)`，阻止版本 ID 与配置/版本号指向不同版本；
- 保留原单列/双列外键兼容，新增组合外键作为权威一致性守卫；
- 约束使用 `information_schema` 守卫逐条添加，干净库、已执行库和部分执行库均可重复执行 V4；不依赖 Flyway/Liquibase。

截至本文件更新时：Java/Stub、Vue 构建和浏览器流程已实测；**MySQL 8 的 V4 仍未执行**，因为本机没有可用演示账号，不能声称 MySQL 通过。已提供环境变量门控的 `M2MysqlIT` 元数据/组合外键测试，未设置 `DB_URL` 时不会运行。
