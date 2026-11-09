# M2 交接说明（最新）

更新时间：2026-11-09（Asia/Shanghai）
状态：**M2 corrective patch 已完成 Stub/浏览器验证；MySQL 未验证；M3 尚未开始。**
M1 基线提交 `77c35d4` 与 M2 原提交 `e72bd3a` 保持未修改、未 amend；corrective patch 使用新的独立提交（哈希见 `git log -1`，最终报告同时记录）。

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
- `work_order_config_version`：发布快照；`UNIQUE(config_id, version_no)`，并新增 `UNIQUE(id, config_id, version_no)` 供三列组合外键引用；只追加，不覆盖。
- `work_order`：工单；`UNIQUE(alert_id)`；保存配置快照、版本、SLA、`requiresReview`、`assignRole`、`due_at`、终态时间；保留原单列/双列外键，并新增 `fk_wo_config_version_consistent(config_version_id, config_id, config_version_no)`。
- `work_order_event`：`CREATED/ASSIGNED/REASSIGNED/CLAIMED/STARTED/RESOLVED/CANCELLED/NOTE_ADDED`，含 actor、角色、备注、requestId。

corrective patch 的数据库改动：

- `ads_alert_review` 增加 `UNIQUE(review_id, alert_id)`；`work_order` 增加 `fk_wo_resolution_review_alert(resolution_review_id, alert_id)`，数据库阻止跨告警复核；
- `work_order_config_version` 的 `UNIQUE(id, config_id, version_no)` 与 `work_order` 的三列外键，阻止版本 ID 与 `(config_id, version_no)` 指向不同版本；
- V4 末尾的 `ALTER` 全部用 `information_schema` 守卫，干净库/已执行库/部分执行库可重复执行；无无条件 `ADD CONSTRAINT`、无 `DROP TABLE`、无清理历史；
- 历史数据违规时组合外键会拒绝添加；只能人工核对修复引用，或暂缓约束，禁止自动删改历史。定位 SQL 写在 `database/README.md`。

V4 不修改 V1/V3 表数据，不 DROP、不回滚。**未在 MySQL 8 执行 V4**：本机有 MySQL 服务但没有可用演示账号，未猜密码。因此 MySQL 的 DDL/FK/CHECK/权限行为均为未验证；`M2MysqlIT` 只有在设置 `DB_URL` 时才运行，本次未运行。发布逻辑已按“先插入版本、再更新 `published_version`”实现；这是静态修正，不等于 MySQL 实测通过。

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
- **幂等判断先于告警/配置状态检查**：先锁告警、查 `UNIQUE(alert_id)`，已有同配置工单即使告警已解决或配置已停用仍返回 200；
- 建单在独立事务中按 `告警 → 配置` 固定顺序锁定；检查、`work_order` 插入、`CREATED` 事件、审计同事务提交。唯一键冲突回滚后，在事务外读取已有工单；Stub 模式用同一临界区模拟；
- `CANCEL/RESOLVE` 备注必填；`RESOLVE` 的 `requiresReview=true` 必须关联同一告警、晚于工单创建、非 INCONCLUSIVE 的复核；
- 详情/创建/动作响应返回服务端计算的 `actions`：`canAssign`、`canAssignSelf`、`canClaim`、`canStart`、`canResolve`、`canCancel`、`canAddNote`；前端不再自行推断权限；
- 事件与审计同事务；终态不可重开/加备注。

## 4. 前端

新增：

- `frontend/src/pages/WorkOrderConfigsPage.vue`：列表/筛选、ADMIN 创建编辑、发布/停用/版本、已发布预览；非 ADMIN 只读快照；无草稿预览按钮；
- `frontend/src/pages/WorkOrderQueuePage.vue`：我的/待领取/全部（仅 ADMIN）、状态筛选、SLA 徽标；
- `frontend/src/pages/WorkOrderDetailPage.vue`：快照、事件、复核候选、分派/领取/开始/完成/取消/备注；动作按钮直接使用后端 `actions`；取消使用页内不可恢复确认框，点“返回”不发送请求，点“确认取消”才发送一次；
- `AlertDetailPage.vue`：显式配置下拉、已有工单展示、同配置幂等、不同配置冲突提示；
- `App.vue` 增加 `workorders/:id`、`workorder-configs` 路由；`shell.css` 增加响应式样式。

未增加图表/ECharts/日志 UI。1440 和 390×844 已检查无横向溢出。

## 5. 测试与验证结果

已实际执行：

| 项目 | 结果 |
|---|---|
| Java 全量 `mvn test` | **50 passed，0 failures/errors，0 skipped**（M2ConfigApiTest 6 + M2WorkOrderApiTest 11，其余为 M1/v1/v2） |
| Python `pytest model-service -q` | **40 passed，1 warning**（Starlette 弃用警告，不涉及 M2 代码） |
| Vue production build | **通过**，2063 modules；JS 153.45 kB / gzip 51.74 kB；CSS 23.19 kB / gzip 5.95 kB |
| Browser `node scripts/browser-check.mjs` | **通过**；1440/901/940/980/390 视口、M1 五页回归、M2 显式转单/领取/开始/完成/取消确认、只读配置预览、队列/详情；`pageErrors=[]`；无横向溢出 |
| Stub 模式 | 已覆盖配置生命周期、版本/预览、权限、幂等、状态机、备注、SLA 字段、可见性、服务端 actions、并发建单 |
| MySQL 模式 | **未运行、未验证**（无凭据）；V4 仅静态审查；`M2MysqlIT` 未运行 |

M2 测试重点覆盖：未登录 401/缺失 CSRF 403、角色 403、草稿不可见、搜索不泄露草稿名称、非法触发器/未知字段、expectedVersion 冲突、发布快照不可变、未发布修改不生效、预览计数、同配置幂等/不同配置 409、配置停用或告警解决后同配置仍幂等、状态迁移非法/终态、取消角色、备注权限、复核完成规则、SLA 使用已发布快照、scope 可见性、旧工单不被前 100 条截断、服务端 actions、两个并发建单只产生一个新建。

## 6. 三个最高风险点与证据

1. **MySQL 组合外键/守卫仍未实测**：`fk_woc_published_version` 的发布顺序、`fk_wo_resolution_review_alert` 的跨告警拒绝、三列 `fk_wo_config_version_consistent` 的版本一致性都只在 Stub/静态层面验证；V4 的 `PREPARE` 守卫是否在目标 MySQL 客户端版本上完整执行也未验证。证据：`M2MysqlIT` 未运行、V4 未执行；不能声称 MySQL 通过。
2. **真实 MySQL 的并发事务窗口**：`WorkOrderCreateWriter` 依赖告警行 `FOR UPDATE` 串行化建单，Stub 用 `synchronized` 模拟；Stub 并发测试证明可观察语义，但不证明 InnoDB 锁等待、`DuplicateKeyException` 翻译、事务回滚和死锁处理。必须在真实 MySQL 上补充并运行并发测试。
3. **历史违规数据会阻止 V4 加约束**：组合外键要求历史工单的复核/版本引用一致；V4 不自动清理，违规库会添加失败。已文档化只读定位 SQL 和人工修复边界，但没有真实库可用于演练。

## 7. 当前运行与清理

`scripts/dev.ps1` 曾启动 8000/8080/5173 进行浏览器验证，验证后应停止本次启动的进程；不得终止用户自己的进程。`runtime/` 下生成浏览器截图和测试日志，已被忽略，不作为交付物。

## 8. 下一阶段

**M3 尚未开始。** 本阶段到此停止，不自动进入 M3，也不扩展日志中心、看板、图表、BPMN 或异步平台。
