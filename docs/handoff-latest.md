# M3 交接说明（最新）

更新时间：2026-11-13（Asia/Shanghai）
状态：**M3 结构化运行日志/ADMIN 查询/前端浮窗已完成 Stub/此前浏览器验证；MySQL 未验证；本轮文档收尾未提交 Git。**
M1 基线提交 `77c35d4`、M2 原提交 `e72bd3a`、M2 corrective patch `9d0f236` 保持未修改、未 amend。M2 最终整改已提交为 `b6e260c`。当前 M3 未提交：19 个 tracked modified、20 个 untracked、0 staged；最终统计见第 8 节。本轮文档收尾未重跑浏览器。

## 1. 本阶段实际范围

M2 只实现：

- 风险告警工单；
- 人工显式转工单（必须选择配置，不自动转换）；
- 配置创建/编辑/发布/停用/版本/已发布预览；
- 分派、领取、开始、完成、取消；
- 处理备注；
- `UNIQUE(alert_id)` 幂等与不同配置冲突；
- 告警 ↔ 工单双向追踪。

M2 阶段明确未做：M3/M4/M5、日志中心/前端日志窗口/任务日志、看板、图表、ECharts、BPMN、MQ、分布式调度、异步任务平台；M3 已在第 8 节实现。未修改 Python、V1/V2 Controller 或响应、认证/CSRF、模型协议、数据源/标签语义、`.env`、`runtime`、`data`、`node_modules`、`dist`、`target`、真实凭据、V1/V2/V3 迁移。本次补充补丁没有修改前端、V4 SQL 或数据库迁移；`V3WorkOrderController.create()` 只把响应渲染切换到 `createDetail()`，普通详情仍走 `detail(id)`。

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

V4 不修改 V1/V3 表数据，不 DROP、不回滚。**V4 对预期结构的干净库、已执行库和缺少对象的部分执行库可重复执行；同名但定义错误的对象不会被自动修复。** 守卫只检查对象名，不比对既有索引/外键/CHECK 的列、引用或定义，因此发布前仍需 schema 预检。

**未在 MySQL 8 执行 V4**：本机有 MySQL 服务但没有可用演示账号，未猜密码。因此 MySQL 的 DDL/FK/CHECK/权限行为均为未验证；`M2MysqlIT` 只有在**同时**设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 时才运行，本次三项均未设置，测试类在 Spring 上下文前跳过。测试源码现有 8 个 `@Test` 方法，覆盖自包含 fixture、V4 两次执行、精确索引/外键/CHECK 预检、违规版本三元组/非法状态/跨告警复核、唯一键翻译、两个建单线程、建单/复核并发、按 `CreateResult.isIdempotent()` 返回 200/201，以及超长 `request_id` 触发 event 写入失败后的回滚断言；V4 文件路径从 `basedir`/`user.dir` 的当前目录和父目录解析，支持在 `backend` 直接运行及仓库根目录 `mvn -f backend/pom.xml`。审计清理只按本次 fixture 的唯一 `request_id`、`target_type='WORK_ORDER'` 和 `action='WORK_ORDER_CREATED'` 删除，不按裸 `target_id`。但这只是源码与规划，8 个方法均未实际运行，不等于真实执行通过。发布逻辑已按“先插入版本、再更新 `published_version`”实现；这是静态修正，不等于 MySQL 实测通过。

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
- 同配置重复建单且已有工单可见：`200 idempotent=true`；不同配置且已有工单可见：`409 WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG`；
- **已有工单不可见时，重复建单不返回详情**：非 ADMIN 且工单已分派给其他处理人时，同配置或不同配置的重复建单均返回 `404 WORK_ORDER_NOT_FOUND`，不泄露 `alert`、`configSnapshot`、`events`、`resolutionReviewCandidates`、`resolutionReview`、`assigneeId`、`createdBy`、`updatedBy`、`dueAt`、`resolvedAt`。普通 `GET /api/v3/work-orders/{id}` 仍返回既有 403，不改为 404；返回详情前按工单 ID 重新读取最新行，writer 的旧快照不能绕过可见性；
- **幂等判断先于告警/配置状态检查**：先锁告警、查 `UNIQUE(alert_id)`，已有同配置工单即使告警已解决或配置已停用仍返回 200；但返回前仍必须通过可见性检查；
- 建单在独立事务中按 `告警 → 配置` 固定顺序锁定；检查、`work_order` 插入、`CREATED` 事件、审计同事务提交。唯一键冲突回滚后，在事务外读取已有工单并再次做可见性/配置判断；Stub 模式用与 `review()` 共享的 `alertMutationLock` 覆盖完整边界，MySQL 仍使用 `SELECT ... FOR UPDATE`，不以 JVM 锁替代 InnoDB；
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
| Java 全量 `mvn test`（2026-11-12 14:49，不含门控 `M2MysqlIT`） | **58 tests，58 passed，0 failures，0 errors，0 skipped**；ApiContract 14 + M2ConfigApiTest 6 + M2WorkOrderApiTest 14 + M2WorkOrderServiceTest 5 + ModelClient 11 + V2ApiTest 8 |
| Java `M2MysqlIT` 单独门控运行（14:50） | **1 test，1 skipped，0 failures/errors**；三个环境变量均未设置，未连接 MySQL；源码 8 个 `@Test` 方法均未执行 |
| Python `.venv\Scripts\python -m pytest model-service -q` | **40 passed，1 warning**（Starlette 弃用警告，不涉及 M2 代码）；0.79s |
| Vue production build | **通过**，2063 modules；JS 153.45 kB / gzip 51.74 kB；CSS 23.19 kB / gzip 5.95 kB；2.24s |
| Browser `node scripts/browser-check.mjs` | **通过**；1440/901/940/980/390 视口、M1 五页回归、M2 显式转单/领取/开始/完成/取消确认、只读配置预览、队列/详情；`pageErrors=[]`；无横向溢出 |
| Stub 模式 | 已覆盖配置生命周期、版本/预览、权限、幂等、状态机、备注、SLA 字段、可见性、服务端 actions、并发建单、重复建单副作用、复核/建单并发、旧快照重新读取后拒绝 |
| MySQL 模式 | **未运行、未验证**（无凭据）；V4 仅静态审查；`M2MysqlIT` 源码扩展但本次跳过 |

M2 测试重点覆盖：未登录 401/缺失 CSRF 403、角色 403、草稿不可见、搜索不泄露草稿名称、非法触发器/未知字段、expectedVersion 冲突、发布快照不可变、未发布修改不生效、预览计数、同配置幂等/不同配置 409、配置停用或告警解决后同配置仍幂等、不可见工单重复建单 404 且无详情字段、可见 owner/ADMIN 幂等、`DuplicateWorkOrderException` 回退不泄露、精确不同配置异常在不可见时转 404、状态迁移非法/终态、取消角色、备注权限、复核完成规则、SLA 使用已发布快照、scope 可见性、旧工单不被前 100 条截断、服务端 actions、两个并发建单只产生一个新建、重试只产生一个工单/一个 `CREATED`/一条审计、复核与建单并发不产生“告警已解决后新建”的可观察结果、writer 旧未分派快照在重新读取后已分派给他人时返回 404。`M2MysqlIT` 源码还包含按 `CreateResult.isIdempotent()` 返回 200/201、唯一 requestId 审计清理、V4 路径解析和超长 requestId 触发 event 写入失败的回滚断言，但均未在真实 MySQL 执行。

Stub 并发测试使用同一 JVM 临界区，证明可观察语义，不证明 MySQL 事务回滚、InnoDB `FOR UPDATE` 等待、唯一键并发或 `DuplicateKeyException` 翻译；这些仍标记为未验证。

## 6. 三个最高风险点与证据

1. **MySQL 组合外键/守卫仍未实测**：`fk_woc_published_version` 的发布顺序、`fk_wo_resolution_review_alert` 的跨告警拒绝、三列 `fk_wo_config_version_consistent` 的版本一致性都只在 Stub/静态层面验证；V4 的 `PREPARE` 守卫是否在目标 MySQL 客户端版本上完整执行也未验证。证据：`M2MysqlIT` 本次跳过、V4 未执行；不能声称 MySQL 通过。
2. **真实 MySQL 的并发事务窗口**：`WorkOrderCreateWriter` 依赖告警行 `FOR UPDATE` 串行化建单，Stub 用 `alertMutationLock`/`reviewLock` 模拟；Stub 并发测试证明可观察语义，但不证明 InnoDB 锁等待、`DuplicateKeyException` 翻译、事务回滚、失败时无孤立 `work_order`/事件/审计，或死锁处理。`M2MysqlIT` 已包含这些方向的测试源码（含超长 `request_id` 的 event 失败回滚断言），但 8 个方法均未运行。
3. **V4 守卫只按对象名判断**：历史违规数据会阻止 V4 加约束，而同名但定义错误的索引/外键/CHECK 不会被自动发现或修复。`M2MysqlIT` 的 schema 预检只在真实数据库运行时才会暴露这种漂移；当前没有真实库可用于演练。

## 7. 当前运行与清理

`scripts/dev.ps1` 曾启动 8000/8080/5173 进行浏览器验证；本次只停止该脚本启动的进程，未终止用户自己的进程。`runtime/` 下生成浏览器截图和测试日志，已被忽略，不作为交付物。

## 8. M3 结构化运行日志

已实现：

- V5 additive `ops_log_event`，独立于 `audit_event`；不修改 V1–V4。
- `OpsLogStore` 的 Stub/MySQL 双实现；Stub 30 天/50,000 条有界。
- `GET /api/v3/ops/logs` 仅 ADMIN；默认 `minLevel=WARN`，ADMIN 可查 INFO，DEBUG 不产生也不可查询；游标、时间范围、source/task/requestId/relatedRequestId 过滤。
- `GET /api/v3/ops/health` 仅 ADMIN；进程启动后未成功探测前为 DEGRADED，状态为进程级，重启后计数清零。
- `POST /api/v3/ops/client-errors` 已登录 + CSRF；每用户/会话 20 次/分钟单实例内存限流；只收固定 `code`、`route`、`relatedRequestId`、`buildVersion`，level/摘要由服务端映射，不接受自由文本/actor/URL/堆栈/详情。
- `requestId` 是上报请求自身的服务端 ID；`relatedRequestId` 是客户端提供的失败请求关联值，只校验 UUID 格式，查询精确匹配。
- 日志写入 `REQUIRES_NEW`，失败只进入 DEGRADED 和限频固定 code 兜底告警，不影响业务；不吞 JVM Error。
- 专用 `OpsLogRetentionJob` 只做 30 天/50,000 条保留清理，每批 1,000、每次最多 10 批，不在业务请求路径做全表计数。
- 前端仅 ADMIN 右下角浮窗；打开且未暂停时每 5 秒轮询；支持 minLevel/source/taskType/relatedRequestId、暂停、加载更早、清屏、空态/断线/403/重试/移动端；文本渲染，无服务器删除。
- 业务成功事件在事务提交/业务调用正常返回后记录；失败不产生成功事件；业务已提交但 HTTP 响应失败时允许同时记录业务成功与 `HTTP_REQUEST_FAILED`，语义不同。

实际结果：

- Java 常规全量：**74 tests，74 passed，0 failures/errors/skipped**；**2026-11-13 10:02**，M3 API 6 + M3 Service 8 + M3 Resilience 2。此前 M3 阶段的 **70 项仅为历史结果**，不是当前实测。
- `M3OpsLogMysqlIT` 源码现有 **5 个 `@Test` 方法**；本次三个 DB 变量未设置，类级跳过（**1 skipped**），5 个方法均未执行，**MySQL canary 未实测**。不写 MySQL 通过。
- Python 40 passed；Vue 构建通过（2064 modules）；浏览器记录为此前 M3 完成阶段，本轮文档收尾未重跑。
- 配置事件抖动：同方法无 sleep 连续运行 **10 次，10/10 通过**，此次未复现；**根因仍未定位**，不能声称已修复。
- M3 统计（最终）：HEAD `b6e260c`；19 个 tracked modified、20 个 untracked、0 staged；tracked **+771/−69**，untracked **20 files / 2,573 lines**，合计 **+3,344/−69**；未提交。
- 详见 [verification.md](verification.md) 与 [api-contract-v3-ops-logs.md](api-contract-v3-ops-logs.md)。

## 9. 文档口径

`docs/acceptance-checklist.md` 仍是历史 v2 验收快照（Java 33、无 M2/M3/V4/V5 章节），本次未改写；它不能作为当前 M3 验收结果。当前口径以 [verification.md](verification.md) 和本文件为准。

## 10. 下一阶段

**M3 已完成但未提交；M4/M5 尚未开始。** 没有扩展看板、图表、ECharts、实时推送、通用调度平台或 Python 改动。
