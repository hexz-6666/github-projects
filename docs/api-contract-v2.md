# v2 API 契约（增量）

状态：v2 是 **v1 的增量层**。v1 的路径、字段、金额精度、错误码、评分/告警/复核语义保持不变；v2 增加会话认证、CSRF、导入、数据质量、审计、模型注册与评测。唯一影响 v1 调用方式的变化是：除健康端点外，v1 业务端点现在要求登录（见 §2），已在 [README §9](../README.md) 记录为 v2 安全语义变化，不改变字段。

所有示例均为虚构数据。金额在浏览器/Java 对外 JSON 中为十进制字符串；Java→Python 评分为精确 JSON 数字。

## 1. 认证与授权

| 角色 | 演示账号 | 可做 |
|---|---|---|
| `ANALYST` | `demo_analyst` | 查看订单、告警、详情、数据质量、模型评估、健康 |
| `REVIEWER` | `demo_reviewer` | 分析员权限 + 提交人工复核 |
| `ADMIN` | `demo_admin` | 复核 + 触发导入/训练 + 查看审计 |

演示密码为虚构值，默认见 §1.3；部署时必须通过 `APP_DEMO_ANALYST_PASSWORD`、`APP_DEMO_REVIEWER_PASSWORD`、`APP_DEMO_ADMIN_PASSWORD` 覆盖。它们是演示账号，不是生产身份系统。

### 1.1 会话

- 登录成功后建立 `JSESSIONID` 会话；Cookie 为 `HttpOnly`、`SameSite=Lax`、本地 HTTP 下 `Secure=false`。
- 会话固定攻击防护：登录时 `changeSessionId()`。
- 未登录访问受保护端点：`401`，`code=UNAUTHENTICATED`。
- 已登录但角色不足：`403`，`code=FORBIDDEN`。
- `POST /api/v2/auth/logout` 需要 CSRF；成功后 `204`。

### 1.2 CSRF

1. `GET /api/v2/auth/csrf` 返回：

```json
{"headerName":"X-XSRF-TOKEN","parameterName":"_csrf","token":"..."}
```

2. 对 `POST/PUT/PATCH/DELETE`，把 `headerName` 与 `token` 放入请求头。
3. 缺失/过期：`403`，`code=CSRF_INVALID`。
4. 登录端点豁免 CSRF 只为允许首次登录；登录后必须重新获取令牌。

### 1.3 认证端点

| 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|
| GET | `/api/v2/auth/csrf` | — | `{headerName,parameterName,token}` |
| POST | `/api/v2/auth/login` | `{username,password}` | `200 {username,roles,authenticated}` |
| GET | `/api/v2/auth/me` | — | `200 {username,roles,authenticated}` |
| POST | `/api/v2/auth/logout` | — | `204` |

登录失败统一 `401 UNAUTHENTICATED`，不区分“用户不存在/密码错误”。登录失败写入审计，但不记录密码。

## 2. 受保护范围

| 范围 | 规则 |
|---|---|
| `/api/v1/health`、`/api/v1/model/health` | 匿名可访问 |
| `/api/v1/orders/**`、`/api/v1/alerts/**` | 任意已登录角色 |
| `POST /api/v1/alerts/{id}/reviews` | `REVIEWER` 或 `ADMIN` |
| `/api/v2/imports/**` | `ADMIN` |
| `POST /api/v2/models/train` | `ADMIN` |
| `/api/v2/audit/**` | `ADMIN` |
| `/api/v2/models`、`/api/v2/evaluations`、`/api/v2/data-quality/summary`、`/api/v2/ieee/summary` | 任意已登录角色 |

复核身份取服务端 `Authentication`，请求体中的 `reviewerId` 仅为 v1 兼容保留，**不再用于写入身份**。

## 3. 统一错误包络

```json
{
  "timestamp":"2026-11-07T10:00:00Z",
  "status":400,
  "code":"INVALID_PARAMETER",
  "message":"参数不合法",
  "path":"/api/v2/imports",
  "requestId":"...",
  "details":[{"field":"sourceSystem","message":"..."}]
}
```

`requestId` 用于排查，不包含敏感输入。错误响应不回显 SQL、堆栈、下游正文、密码、Cookie 或原始载荷。

v2 新增错误码：

| HTTP | code | 含义 |
|---|---|---|
| 400 | `IMPORT_SOURCE_NOT_FOUND` | 本地数据目录/必需文件缺失 |
| 400 | `INVALID_PARAMETER` | sourceSystem/version/path 等不合法 |
| 401 | `UNAUTHENTICATED` | 未登录或登录失败 |
| 403 | `FORBIDDEN` | 角色不足 |
| 403 | `CSRF_INVALID` | CSRF 缺失/过期 |
| 404 | `IMPORT_BATCH_NOT_FOUND` | 批次不存在 |
| 409 | `ALERT_VERSION_CONFLICT` | 复核乐观锁冲突（v1） |
| 409 | `ALERT_ALREADY_RESOLVED` | 告警已解决（v1） |
| 502 | `MODEL_UNAVAILABLE` | Python 不可达/超时 |
| 502 | `MODEL_INVALID_RESPONSE` | 模型响应违反契约或产物缺失 |

## 4. 导入

### `POST /api/v2/imports`

请求：

```json
{"sourceSystem":"OLIST"}
```

`sourceSystem` 仅允许 `OLIST`、`IEEE_CIS`。服务端从 `app.import-root`（默认 `data/external`，并兼容从 `backend/` 启动时的项目根回退）读取：

| 来源 | 必需文件 | 可选 |
|---|---|---|
| OLIST | `olist/olist_orders_dataset.csv`、`olist/olist_order_payments_dataset.csv` | `olist/olist_customers_dataset.csv` |
| IEEE_CIS | `ieee-cis/train_transaction.csv` | — |

成功 `200`：

```json
{
  "batchId":"IMP-1762509600000-a1b2c3",
  "sourceSystem":"OLIST",
  "datasetName":"olist-brazilian-ecommerce",
  "idempotencyKey":"<sha256>",
  "status":"SUCCEEDED",
  "totalRows":2,
  "acceptedRows":2,
  "rejectedRows":0,
  "fileCount":2,
  "operatorId":"demo_admin",
  "errorSummary":null,
  "startedAt":"2026-11-07T10:00:00Z",
  "finishedAt":"2026-11-07T10:00:01Z",
  "idempotent":false
}
```

- 幂等键 = 来源 + 必需文件内容 SHA-256。相同文件重复提交返回已有批次，`idempotent=true`，不重复写入。
- `status`：`RUNNING`/`SUCCEEDED`/`PARTIAL`/`FAILED`。有接收但部分拒绝为 `PARTIAL`；全部拒绝为 `FAILED`。
- 并发同文件导入的竞态未被 MySQL 真实环境验证；数据库唯一键会拒绝重复插入，但当前未把该竞态转换成幂等响应。
- 导入不写 `is_fraud` 到订单表，不把 Olist 状态/取消/退款当欺诈标签。

### `GET /api/v2/imports?page=0&size=20`

`ADMIN`；返回 `{items:[batch...],page:{number,size,totalElements,totalPages}}`。

### `GET /api/v2/imports/{batchId}`

`ADMIN`；返回单个批次；不存在 `404 IMPORT_BATCH_NOT_FOUND`。

### `GET /api/v2/imports/{batchId}/quality`

`ADMIN`；返回 `{batch,checks}`。

### `GET /api/v2/data-quality/summary`

任意登录角色；返回最近批次：

```json
{
  "batch": { "...": "同上" },
  "checks": [
    {
      "batchId":"IMP-...",
      "checkCode":"ORDER_ROW_COUNT",
      "severity":"ERROR",
      "passed":true,
      "observed":"2",
      "expected":">0",
      "detail":"订单总行数",
      "createdAt":"..."
    }
  ]
}
```

无批次时 `batch=null`、`checks=[]`，并有 `message`。检查只描述导入质量，不是模型效果或欺诈真值。

## 5. 审计

### `GET /api/v2/audit?page=0&size=20`

`ADMIN`；返回：

```json
{
  "items":[{
    "eventId":1,
    "actorId":"demo_admin",
    "actorRole":"ROLE_ADMIN",
    "action":"IMPORT_RUN",
    "targetType":"IMPORT_BATCH",
    "targetId":"IMP-...",
    "result":"SUCCESS",
    "requestId":"...",
    "detail":{"sourceSystem":"OLIST","acceptedRows":2,"rejectedRows":0},
    "createdAt":"..."
  }],
  "page":{"number":0,"size":20,"totalElements":1,"totalPages":1}
}
```

审计为追加式；`detail` 只放非敏感计数/类型，不放请求正文、密码、Cookie、邮箱或原始载荷。已记录动作：`LOGIN`、`SCORE_RUN`、`REVIEW_SUBMIT`、`IMPORT_RUN`、`MODEL_TRAIN`。

## 6. 模型注册与评测

### `POST /api/v2/models/train`

`ADMIN`；请求：

```json
{"modelVersion":"ieee-logreg-v1"}
```

约束：`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`，且不得为 `synthetic-rule-v1`；必须存在 `ieee-cis/train_transaction.csv`。Java 调 Python `/internal/v2/train`，校验响应后写 `model_registry` 与 `model_evaluation`，并写审计。

成功 `200`：

```json
{
  "model":{
    "modelVersion":"ieee-logreg-v1",
    "modelType":"TRAINED_LOGREG",
    "sourceSystem":"IEEE_CIS",
    "artifactPath":"runtime/models/ieee-logreg-v1.json",
    "featureSchema":["TransactionAmt","card1"],
    "trainedAt":"2026-11-07T10:00:00Z",
    "trainingRows":210,
    "positiveRate":0.052381,
    "status":"ACTIVE"
  },
  "evaluation":{
    "modelVersion":"ieee-logreg-v1",
    "sourceSystem":"IEEE_CIS",
    "splitStrategy":"TIME_70_15_15",
    "entityGrouping":"CARD_ADDR_PURGED",
    "validRowsDroppedForEntity":0,
    "testRowsDroppedForEntity":0,
    "trainRows":210,"validRows":45,"testRows":45,"testPositives":2,
    "baseRate":0.044444,
    "prAuc":1.0,"rocAuc":1.0,
    "recallAtFpr1":1.0,"recallAtFpr5":1.0,
    "precisionAt100":0.044444,
    "brier":0.000001,
    "threshold":0.5,
    "confusion":{"tp":2,"fp":0,"tn":43,"fn":0}
  },
  "labelSemantics":"MODEL_SCORE_NOT_GROUND_TRUTH"
}
```

`prAuc=1.0` 只可能来自完全可分的合成烟测夹具，不能当真实效果。

### `GET /api/v2/models`

任意登录角色；返回模型数组。MySQL 模式含 `modelId`、`createdAt`；stub 模式字段为契约子集。模型分仍不是真实欺诈概率。

### `GET /api/v2/evaluations`

任意登录角色；返回评测数组。指标字段：

`trainRows`、`validRows`、`testRows`、`testPositives`、`baseRate`、`prAuc`、`rocAuc`、`recallAtFpr1`、`recallAtFpr5`、`precisionAt100`、`brier`、`threshold`、`confusion`；v2 评测还记录 `splitStrategy`、`entityGrouping`、`validRowsDroppedForEntity`、`testRowsDroppedForEntity`。

### `GET /api/v2/ieee/summary`

任意登录角色；返回 `{transactions,sourceSystem:"IEEE_CIS",labelSemantics:"IEEE_CIS_TRANSACTION_LABEL_ONLY",orderJoinAllowed:false}`。只统计独立评测域，不返回订单关联。

## 7. Python 内部 v2 契约

这些端点只绑定回环地址，由 Java 调用，不对浏览器开放。

### `POST /internal/v2/train`

请求：

```json
{"modelVersion":"ieee-logreg-v1","dataPath":"E:/beysesji/data/external/ieee-cis/train_transaction.csv","sourceSystem":"IEEE_CIS"}
```

- 路径必须位于服务端 `MODEL_DATA_ROOT`（默认项目根 `data/external`）下，文件名必须是 `train_transaction.csv`。
- 只接受 `sourceSystem=IEEE_CIS`。
- 成功返回模型元数据、`artifactPath="runtime/models/<version>.json"`、特征名与指标。
- 数据不足/分段无正样本/缺列：`400`；路径越界：`400`。
- 模型产物写 `MODEL_ARTIFACT_ROOT`（默认项目根 `runtime/models`），不进 Git。

### `POST /internal/v2/score`

```json
{"modelVersion":"ieee-logreg-v1","features":{"TransactionAmt":1200.0,"card1":1003}}
```

返回 `{modelVersion,modelType,sourceSystem,score,threshold,decision,labelSemantics}`。`features` 必须为有限数值；未知版本 `404`。当前 Java v1 评分不调用此端点，保留给后续受控集成。

### `GET /internal/v2/models`

列出 `runtime/models/*.json` 的元数据；损坏文件跳过。

## 8. 训练/评测语义（不可静默修改）

- 标签只来自 IEEE-CIS `isFraud`，只在 IEEE-CIS 域内有效；不得外推到 Olist。
- 按 `TransactionDT` 排序后 70/15/15 时间切分；禁止随机切分。
- 以 `card1+addr1` 为实体键，从验证/测试段清除训练段已见实体；验证段已见实体也不得进入测试。实体键缺失时退化为唯一 `TransactionID`，不会误合并。
- 记录 `entityGrouping=CARD_ADDR_PURGED`、`validRowsDroppedForEntity`、`testRowsDroppedForEntity`、`entityOverlap=0`；清除后某段为空/无正样本则训练失败，不静默回填。
- 预处理只在训练段拟合；阈值只在验证段选择；测试段只评估一次。
- 特征只使用交易时可见字段；不使用 `isFraud`、`TransactionID` 作为特征，不做目标编码，不用测试段调参。
- 指标至少包含 PR-AUC、ROC-AUC、Recall@FPR 1%/5%、Precision@100、Brier、混淆矩阵与基率；不能只报准确率。
- 纯标准库实现、单机、演示规模；训练段超过 20,000 行时确定性抽样，验证/测试段保持完整。
- `modelType` 为 `TRAINED_LOGREG` 或 `TRAINED_*`；不得复用 `DEMO_RULE`。

## 9. v1 兼容性

- v1 路径、字段、金额字符串、`201 + Location`、`409`、健康语义不变。
- 唯一调用方式变化：v1 业务端点现在要求会话登录；健康端点仍匿名。
- 请求体 `reviewerId` 仍可发送以兼容旧客户端，但服务端忽略其身份，写入认证用户名。
- v1 固定规则仍为 `DEMO_RULE` / `synthetic-rule-v1`，分数不是真实概率。
