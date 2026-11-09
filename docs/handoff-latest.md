# M2 交接说明（最新）

更新时间：2026-11-09（Asia/Shanghai）
状态：**M2 已实现并完成 Stub/浏览器验证；M3 尚未开始。**
M1 基线提交 `77c35d4` 保持未修改；M2 使用独立提交（提交哈希见 `git log -1`，最终报告同时记录）。

## 1. 本阶段实际范围

M2 只实现：

- 风险告警工单；
- 人工显式转工单（必须选择配置，不自动转换）；
- 配置创建/编辑/发布/停用/版本/已发布预览；
- 分派、领取、开始、完成、取消；
- 处理备注；
- `UNIQUE(alert_id)` 幂等与不同配置冲突；
- 告警 ↔ 工单双向追踪。

明确未做：M3/M4/M5、日志中心/前端日志窗口/任务日志、看板、图表、ECharts、BPMN、MQ、分布式调度、异步任务平台。未修改 Python、V1/V2 Controller 或响应、认证/CSRF、模型协议、数据源/标签语义、`.env`、`runtime`、`data`、`node_modules`、`dist`、`target`、真实凭据、V1/V2/V3 迁移。

## 2. 数据库

新增迁移：`database/migrations/V4__v3_work_orders.sql`，仅新增四表：

- `work_order_config`：配置当前草稿与生命周期，`status=DRAFT/ACTIVE/DISABLED`，无 `enabled` 列，`published_version` 指向不可变版本；唯一 `code`。
- `work_order_config_version`：发布快照；`UNIQUE(config_id, version_no)`；只追加，不覆盖。
- `work_order`：工单；`UNIQUE(alert_id)`；保存配置快照、版本、SLA、`requiresReview`、`assignRole`、`due_at`、终态时间；复合外键 `(id, published_version)` 在配置与版本两表建立后添加。
- `work_order_event`：`CREATED/ASSIGNED/REASSIGNED/CLAIMED/STARTED/RESOLVED/CANCELLED/NOTE_ADDED`，含 actor、角色、备注、requestId。

V4 不修改 V1/V3 表，不 DROP、不回滚、不清理历史。**未在 MySQL 8 执行 V4**：本机有 MySQL 服务但没有可用演示账号，未猜密码。因此 MySQL 的 DDL/FK/CHECK/权限行为均为未验证；只做了静态审查。发布逻辑已按“先插入版本、再更新 `published_version`”实现，以满足复合外键；这属于静态修正，不等于 MySQL 实测通过。

## 3. 后端与 API

主要新增：

- `api/V3WorkOrderConfigController`、`api/V3WorkOrderController`；
- `service/WorkOrderConfigService`、`WorkOrderService`、`WorkOrderCreateWriter`、`WorkOrderMatcher`、`AssigneeDirectory`；
- `store/StubWorkOrderConfigStore`、`StubWorkOrderStore`、`MysqlWorkOrderConfigStore`、`MysqlWorkOrderStore`；
- 对应 domain 与 DTO。

主要端点：

- 配置：`GET/POST /api/v3/work-order-configs`，`GET/PUT /{id}`，`POST /{id}/publish`，`POST /{id}/disable`，`GET /{id}/versions`，`POST /preview`。
- 工单：`GET /api/v3/work-orders`、`/{id}`、`/assignees`，`POST /{id}/assign`、`/{id}/transitions`、`/{id}/notes`。
- 转换/追踪：`GET /api/v3/alerts/{id}/work-order-candidates`、`GET/POST /api/v3/alerts/{id}/work-orders`。

安全矩阵：

- 配置创建/更新/发布/停用：ADMIN；
- 预览、转工单、分派、状态迁移、备注：REVIEWER/ADMIN；
- 可指派账号：ADMIN；
- 工单列表/详情：任意登录，按 scope/assignee 可见性约束；`scope=all` 仅 ADMIN；
- 非 ADMIN 只读已发布配置快照，草稿字段、版本号、未发布差异不返回；关键字搜索也按已发布快照匹配。

关键语义：

- 触发字段白名单：`sourceSystems`、`riskLevels`、`alertStatuses`、`reasonCodes`；不允许表达式/脚本/SQL；
- `riskScoreThreshold` 顶层，`score >= threshold`，标签固定“风险分阈值（非欺诈概率）”；
- 预览只使用最后一次已发布版本，不提供草稿预览；DISABLED 不可预览/候选；
- 同配置重复建单 `200 idempotent=true`；不同配置 `409 WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG`；
- `CANCEL/RESOLVE` 备注必填；`RESOLVE` 的 `requiresReview=true` 必须关联同一告警、晚于工单创建、非 INCONCLUSIVE 的复核；
- 事件与审计同事务；终态不可重开/加备注。

## 4. 前端

新增：

- `frontend/src/pages/WorkOrderConfigsPage.vue`：列表/筛选、ADMIN 创建编辑、发布/停用/版本、已发布预览；非 ADMIN 只读快照；无草稿预览按钮；
- `frontend/src/pages/WorkOrderQueuePage.vue`：我的/待领取/全部（仅 ADMIN）、状态筛选、SLA 徽标；
- `frontend/src/pages/WorkOrderDetailPage.vue`：快照、事件、复核候选、分派/领取/开始/完成/取消/备注；按角色隐藏动作，后端仍强制校验；
- `AlertDetailPage.vue`：显式配置下拉、已有工单展示、同配置幂等、不同配置冲突提示；
- `App.vue` 增加 `workorders/:id`、`workorder-configs` 路由；`shell.css` 增加响应式样式。

未增加图表/ECharts/日志 UI。1440 和 390×844 已检查无横向溢出。

## 5. 测试与验证结果

已实际执行：

| 项目 | 结果 |
|---|---|
| Java 全量 `mvn test` | **45 passed，0 failures/errors，0 skipped**（含 M2ConfigApiTest、M2WorkOrderApiTest；总计 45） |
| Python `pytest model-service -q` | **40 passed，1 warning**（Starlette 弃用警告，不涉及 M2 代码） |
| Vue production build | **通过**，2063 modules；JS 152.63 kB / gzip 51.55 kB；CSS 22.57 kB / gzip 5.82 kB |
| Browser `node scripts/browser-check.mjs` | **通过**；1440/390 登录、M1 五页回归、M2 显式转单/领取/开始/完成、只读配置预览、队列/详情均通过；无页面错误、无横向溢出 |
| Stub 模式 | 已覆盖配置生命周期、版本/预览、权限、幂等、状态机、备注、SLA 字段、可见性 |
| MySQL 模式 | **未运行、未验证**（无凭据）；V4 仅静态审查 |

M2 测试重点覆盖：未登录 401/角色 403、草稿不可见、搜索不泄露草稿名称、非法触发器/未知字段、expectedVersion 冲突、发布快照不可变、未发布修改不生效、预览计数、同配置幂等/不同配置 409、状态迁移非法/终态、取消角色、备注权限、复核完成规则、SLA 使用已发布快照、scope 可见性。

## 6. 三个最高风险点与证据

1. **MySQL 发布顺序与复合外键**：`work_order_config.published_version` 引用 `work_order_config_version(config_id,version_no)`，必须先插入版本再更新配置。已在 `WorkOrderConfigService.publish` 调整顺序；但 MySQL 未执行，仍属静态修正。证据：V4 第 67–69 行、`publish` 当前实现；Stub 测试不能证明 MySQL FK 行为。
2. **幂等与并发唯一键**：依赖 `UNIQUE(alert_id)` 与独立事务 `WorkOrderCreateWriter`；同配置 200、不同配置 409 已由 `M2WorkOrderApiTest` 覆盖。但真实 MySQL 的 `DuplicateKeyException` 翻译、事务回滚和并发窗口未在数据库实测。
3. **可见性与草稿泄漏**：非 ADMIN 配置列表/详情/搜索使用已发布快照，工单详情限制未分派/本人/ADMIN。已由 API 测试和浏览器只读配置页覆盖；但仍需在真实 MySQL 上确认查询/分页与并发下无越权。

## 7. 当前运行与清理

`scripts/dev.ps1` 曾启动 8000/8080/5173 进行浏览器验证，验证后应停止本次启动的进程；不得终止用户自己的进程。`runtime/` 下生成浏览器截图和测试日志，已被忽略，不作为交付物。

## 8. 下一阶段

**M3 尚未开始。** 本阶段到此停止，不自动进入 M3，也不扩展日志中心、看板、图表、BPMN 或异步平台。
