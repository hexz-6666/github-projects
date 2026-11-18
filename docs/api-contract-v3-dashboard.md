# v3 效能总览契约（M4）

状态：M4 增量契约。新增 ADMIN 只读聚合看板；不修改 v1/v2/M2/M3 接口，不包含 IEEE-CIS 模型评测合并，不包含雷达图。

## 1. 边界

- 仅聚合订单、风险告警、工单、复核、导入批次和 DQ；不返回订单/客户/复核正文。
- 订单按 `dwd_order.data_origin` 分开统计；不汇总跨币种金额。
- IEEE-CIS 模型评测属于独立域，不进入本 API 或订单趋势。
- 风险分不是欺诈概率，风险告警不是欺诈事实，复核结论不是客观真值，DQ 通过不等于模型有效。
- 前端仅 ADMIN 可见入口；后端权限是最终控制。

## 2. 端点

```text
GET /api/v3/dashboard/overview?from=&to=&bucket=day|week
```

- 权限：ADMIN。
- `from`/`to`：ISO-8601 时间；UTC 归一化；`from` 含、`to` 不含。
- 最大范围：366 天。
- 默认：最近 366 天，`bucket=week`。
- `bucket=day` 最多 90 天。
- 周按 ISO 周一 00:00 UTC 切分。
- 不提供全部历史。
- 首尾不完整 bucket 返回 `partial=true`。

## 3. 响应

```json
{
  "range": {
    "from": "2026-01-01T00:00:00Z",
    "to": "2026-11-13T00:00:00Z",
    "bucket": "week",
    "timezone": "UTC",
    "maxDays": 366
  },
  "hasData": true,
  "totals": {
    "orders": 3,
    "alerts": 2,
    "newWorkOrders": 0,
    "reviewResolved": 0
  },
  "buckets": [
    {"start": "2025-12-29", "end": "2026-01-05", "partial": true}
  ],
  "orders": {
    "hasData": true,
    "total": 3,
    "series": [
      {"origin": "SYNTHETIC", "total": 3, "points": [3]}
    ]
  },
  "alerts": {"hasData": true, "total": 2, "points": [2]},
  "workOrders": {"hasData": false, "total": 0, "points": [0]},
  "reviews": {
    "hasData": false,
    "total": 0,
    "points": [0],
    "byResult": [
      {"key": "CONFIRMED", "count": 0},
      {"key": "REJECTED", "count": 0},
      {"key": "INCONCLUSIVE", "count": 0}
    ]
  },
  "snapshot": {
    "asOf": "2026-11-13T03:00:00Z",
    "asOfKind": "REQUEST_GENERATION_TIME",
    "hasData": true,
    "alertsHasData": true,
    "workOrdersHasData": false,
    "alertsByRiskLevel": [
      {"key": "LOW", "count": 1},
      {"key": "MEDIUM", "count": 0},
      {"key": "HIGH", "count": 1}
    ],
    "workOrdersByStatus": [
      {"key": "OPEN", "count": 0},
      {"key": "ASSIGNED", "count": 0},
      {"key": "IN_PROGRESS", "count": 0},
      {"key": "RESOLVED", "count": 0},
      {"key": "CANCELLED", "count": 0}
    ]
  },
  "imports": {
    "hasData": false,
    "batches": []
  },
  "dq": {
    "hasData": false,
    "scope": "ALL_ENDED_BATCHES",
    "totalChecks": 0,
    "evaluatedChecks": 0,
    "infoChecks": 0,
    "infoFailed": 0,
    "failedError": 0,
    "failedWarn": 0,
    "passRate": null,
    "bySource": []
  }
}
```

### 空数据

- 趋势 `points` 与 `buckets` 等长并补 0；`hasData=false`。
- 分布返回固定枚举类别，计数为 0。
- 批次/DQ 列表返回 `[]`。
- 不返回随机或 mock 数据。
- 顶层与各 section 都有 `hasData`。

## 4. 指标口径

### 订单趋势

- 来源：`dwd_order.ordered_at`、`data_origin`。
- 统计：按 UTC bucket `COUNT(*)`，按实际来源分组；不合并来源，不计算金额。
- 只返回实际存在的 `data_origin` series。

### 告警趋势 / 新建工单 / 复核推进

- 告警：`ads_risk_alert.created_at`。
- 新建工单：`work_order.created_at`。
- 复核推进：`ads_alert_review.new_status='RESOLVED'` 且按 `created_at` 归桶；称“次数”，不是工单完成量。
- 当前状态不是历史状态；不把当前 `status` 过滤后的创建量称为历史状态趋势。
- 复核推进只按 `ads_alert_review.new_status='RESOLVED'` 计数；不按 `review_result` 二次过滤。

### 当前快照

- `snapshot.asOf` 为一次请求开始时的服务端生成时间；`asOfKind=REQUEST_GENERATION_TIME`。它不是历史数据库一致性快照，多个查询之间不承诺跨表同一事务视图。
- 告警风险级别和工单状态是当前读取结果，不受 `from/to` 过滤。
- `snapshot.hasData` 为合并值；`alertsHasData` 和 `workOrdersHasData` 必须分别控制两张图。
- 工单仅在 `status='RESOLVED'` 时称“已解决工单”。

### 导入批次

- 终态：`SUCCEEDED`、`PARTIAL`、`FAILED`；排除 `RUNNING`。
- 范围内按 `started_at` 倒序，同时间按 `batch_id` 倒序稳定排序；最多 20 个。
- 展示状态、来源、接收行数、拒绝行数；FAILED 不标成成功。

### DQ

- 检查表：`dq_check_result(batch_id,severity,passed,created_at)`。
- 仅统计已结束批次的检查项。
- 通过率只使用 `ERROR/WARN`：
  - 分子：`passed=1`
  - 分母：`severity IN ('ERROR','WARN')`
  - 无 ERROR/WARN 检查项时 `passRate=null`。
- INFO 不进入通过率分子/分母，单独返回 `infoChecks`/`infoFailed`。
- `failedError`、`failedWarn` 分别计数。
- 顶层 `scope=ALL_ENDED_BATCHES`，表示 Olist 与 IEEE-CIS 的全局汇总；顶层 `passRate` 是按检查项加权的跨来源合并参考值，不是任一来源的质量结论。
- `bySource` 必须按 `etl_import_batch.source_system` 分开给出同一组指标，前端以它作为主要来源判断；首版 UI 不突出顶层 `passRate`，只展示各来源通过率和全局检查项数量。
- 历史归桶按 `etl_import_batch.started_at`。
- 导入接收/拒绝行数与 DQ 通过/失败分开。

## 5. 错误

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_PARAMETER` | 时间格式、from/to 缺失一个、范围 >366 天、day >90 天、bucket 非法 |
| 401 | `UNAUTHENTICATED` | 未登录 |
| 403 | `FORBIDDEN` | 非 ADMIN |

## 6. 非目标

- 不实现雷达图。
- 不合并 Olist 与 IEEE-CIS。
- 不返回模型评测指标。
- 不新增图表依赖。
- 不新增 V6 索引。
