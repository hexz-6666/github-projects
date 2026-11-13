# 实际验证记录

**验证日期：2026-11-09（Asia/Shanghai）；2026-11-12 增补 M2 可见性/锁补丁。** 本文件记录本机实际执行的结果，不代表生产验收，也不把 stub 当作 MySQL 成功。除特别说明外，运行数据为独立虚构/合成夹具；未下载官方 Olist/IEEE-CIS 数据。2026-11-09 增补 v3 M1 登录页、记住账号、壳层与移动端抽屉收尾验收。

## 1. 环境与范围

- Windows；JDK 8（1.8.0_451）、Maven 3.9.11、Python 3.12.9、Node.js 24.7.0。
- MySQL 8.0 服务与 3306 端口存在，但**没有可用演示账号**，未猜密码。
- 无 Docker/Kafka/Hadoop/Kubernetes；无生产 IAM、TLS、KMS、备份恢复实战。
- v2 代码已实现五页、会话认证/RBAC/CSRF、导入/质量/审计、IEEE 训练评测；v1 契约保持。
- v3 M1 已实现登录页/记住账号/紧凑壳层和现有五页适配；M1 未新增后端、API、数据库迁移、工单、日志中心、看板或 ECharts。**2026-11-09 增补 M2：工单/配置 API、V4（仅静态）、工单前端与浏览器流程；随后完成 M2 corrective patch（建单行锁/幂等顺序、组合外键、服务端 actions、取消确认、验收测试）；2026-11-12 增补 M2 可见性/Stub 锁补丁；M3 已实现结构化运行日志、ADMIN 查询/健康 API 和前端浮窗。**

## 2. 自动化构建与测试

最终命令：

```powershell
.\scripts\verify.ps1
```

结果 **通过（退出码 0）**：

| 项目 | 实际结果 | 范围 |
|---|---|---|
| Python pytest | **40 passed**，1 条 Starlette `BlockingPortal` 弃用警告 | v1 32 + v2 训练/评测 8 |
| Java Maven test（M1 当时） | **33 passed，0 failures/errors** | ApiContract 14 + ModelClient 11 + V2ApiTest 8；这是历史 v2 基线；M2 为 58，M3 为 **74 passed**，见 §8/§9 |
| Vue production build（M1 收尾最终） | **通过**，2060 modules，JS **117.47 kB / gzip 42.62 kB**，CSS **19.49 kB / gzip 5.11 kB**，登录 WebP 33.99 kB | Vue 3.5.45 / Vite 6.4.5 |
| M1 收尾前检查点 | 2060 modules，JS 116.84 kB / gzip 42.40 kB，CSS 19.36 kB / gzip 5.09 kB | 收尾增加焦点/ARIA/作用域样式后为上一行最终值 |
| `npm audit --audit-level=moderate` | **0 vulnerabilities** | 仅前端锁定依赖，不等于 Java/Python 全依赖无漏洞 |
| `pip check` | No broken requirements found | 依赖一致性，不是漏洞扫描 |

M1 图标依赖：`lucide-vue-next@1.0.0` 已被 npm 标记 deprecated，实际改用维护中的 **`@lucide/vue@1.70.0`**（ISC 许可证，peer `vue >=3.0.1`，锁定精确版本）；只导入壳层实际使用的 12 个图标。未引入 ECharts、UI 框架或外部图片/字体。`npm audit --audit-level=moderate` 为 **0 vulnerabilities**。

Java v2 覆盖：匿名 401、分析员越权 403、会话复核身份、Olist 导入幂等、质量检查、缺失目录受控错误、模型注册/评测、非法模型响应 502、审计仅 ADMIN。既有 v1 测试已适配 Session/CSRF，断言字段未放宽。

Python v2 覆盖：时间切分严格递增、`card1+addr1` 实体清除（验证/测试不重叠、丢弃计数）、指标字段、产物评分范围、无正样本/缺列拒绝、版本不能复用 `synthetic-rule-v1`、路径越界 400、额外字段 422。

## 3. 真实进程与 HTTP 联调

`scripts/dev.ps1` 在 8000/8080/5173 空闲时启动三端，输出 `Aggregate health: DEGRADED`（stub 预期）。

| 场景 | 实际结果 |
|---|---|
| Java 健康（stub + 模型 UP） | **503 DEGRADED**；`storageMode=stub`；`database.status=BYPASS`；模型 UP |
| 匿名 `GET /api/v1/orders` | **401** `UNAUTHENTICATED` |
| 登录 `demo_reviewer` | **200**；`roles=["REVIEWER"]`；会话建立 |
| `GET /api/v2/auth/me` | 200 |
| 无 CSRF 的复核 POST | **403** `CSRF_INVALID` |
| 带 CSRF 的复核 POST | **201**；详情 `reviewerId=demo_reviewer`（忽略请求体伪造值） |
| 复核员访问 `/api/v2/audit` | **403** `FORBIDDEN` |
| 管理员登录后审计 | **200** |
| 缺失 Olist 目录导入 | **400** `IMPORT_SOURCE_NOT_FOUND`，不回显绝对路径 |
| 缺失 IEEE 文件训练 | **400** `IMPORT_SOURCE_NOT_FOUND` |

### 3.1 端到端合成烟测（非真实数据）

为验证“导入 → 训练 → 注册”链路，临时生成并放入：

- Olist 结构夹具 2 行（1 delivered、1 canceled，BRL 支付）；
- IEEE 结构夹具 300 行（每 20 行 1 个正类，金额强相关）。

实际结果：

| 步骤 | 结果 |
|---|---|
| 导入 OLIST | 200 `SUCCEEDED`，accepted 2 |
| 导入 IEEE_CIS | 200 `SUCCEEDED`，accepted 300 |
| 订单关键字 `OLIST` | 200，2 行；来源 `OLIST`，金额 BRL |
| 训练 `ieee-smoke-v1` | 200；`TRAINED_LOGREG`；`sourceSystem=IEEE_CIS` |
| 切分/实体 | `TIME_70_15_15` + `CARD_ADDR_PURGED`；`entityOverlap=0`；清除 0 行（夹具实体唯一） |
| 切分计数 | train 210 / valid 45 / test 45；时间递增 |
| 指标 | PR-AUC 1.0、ROC-AUC 1.0、Brier≈0；**只说明夹具完全可分，不是模型效果** |
| 注册/评测/审计 | 1 模型、1 评测、4 条审计 |
| 产物 | `runtime/models/ieee-smoke-v1.json`，Java 校验存在且路径合法 |

随后删除了合成外部文件和模型产物，并用空状态重新验收，避免把烟测数据留作“真实结果”。

## 4. 浏览器自动化

使用临时安装的 `playwright-core@1.55.0` + 本机 Edge headless，通过 `PLAYWRIGHT_MODULE` 指向 `runtime/browser-tools`。Playwright 不进入生产依赖；`runtime/` 被 Git 忽略。

命令：

```powershell
$env:PLAYWRIGHT_MODULE='E:\beysesji\runtime\browser-tools\node_modules\playwright-core'
node scripts\browser-check.mjs
```

M1 收尾通过断言：

- 登录页 1440 双栏、390 单栏；`username`/`current-password` autocomplete；演示账号只填用户名；错误密码不建立会话。
- 记住账号：首次错误密码不写新用户名；已有 `demo_admin` 时用错误密码登录 `demo_reviewer` 不覆盖旧值；只有登录成功后才写入；取消勾选立即删除；退出登录恢复预填。
- 浏览器 Web Storage 与 Cookie 中无密码；源码扫描 `frontend/src` 12 个文件，未使用 IndexedDB（本检查不声称扫描 IndexedDB 记录）。
- 登录轮换 `JSESSIONID`；退出后旧 Cookie 返回 401。
- 五个页面均有 1440 与 390 截图；订单状态筛选/重置、告警风险筛选、告警详情复核、数据质量只读边界、模型评估只读边界、健康刷新均实际执行。
- `shell.css` 的页面样式限定在 `.shell` 下；浏览器检查 page-head/filters/table/card 的实际 computed style，不只检查标题存在。
- 移动抽屉：关闭后几何完全移出视口且无遮罩；`aria-expanded`、`aria-controls`、稳定 `#app-sidebar`；关闭时 `aria-hidden="true"` + `inert`；打开时焦点进入关闭按钮；Escape、遮罩、关闭按钮、导航跳转均关闭并把焦点还给打开按钮；关闭状态按 8 次 Tab 不会进入侧栏。
- 抽屉关闭等待使用几何 `waitForFunction`（总超时 3s），不是固定 sleep；390×844 五个页面均无横向溢出。
- 新匿名会话回到登录页；页面 `pageerror` 为空。

截图位于 `runtime/ui-v3-*.png`（本机忽略目录），包括 `ui-v3-login-1440.png`、`ui-v3-orders-1440.png`、`ui-v3-alerts-1440.png`、`ui-v3-quality-1440.png`、`ui-v3-models-1440.png`、`ui-v3-health-1440.png`、`ui-v3-drawer-open-390.png` 及五个 `*-390.png`。**模型不支持读图，因此没有声称人工审阅截图的视觉美观性或完整可访问性**；以上仅为 DOM/布局/错误断言，截图供人工复核。

## 5. 清理

- 最终验证后停止 `dev.ps1` 启动的三端，确认 8000/8080/5173 无监听。
- 删除 `data/external/olist`、`data/external/ieee-cis` 和 `runtime/models` 中的烟测文件；仓库/工作区不保留外部数据或模型产物。
- 实体清除的独立单测在 Python 中覆盖：重复 `card1+addr1` 从验证/测试段被移除，丢弃计数 20/30，三段仍严格时间递增。
- 未使用按端口/名称的全局强杀，未触碰 DeepSeek Harness GUI。

## 6. 隐私、许可与仓库检查

- 源码扫描未发现请求正文、SQL 参数、密码、Cookie、邮箱或原始载荷的业务日志；演示密码只出现在明确的虚构账号配置/文档中。
- 错误响应不回显非法输入、下游正文、堆栈、SQL 或内部 URL。
- 前端只请求相对 `/api`；无 `v-html`、外部图片/字体；CSRF 令牌不写入 localStorage。登录页 WebP 为项目内原创程序化资产（来源记录见 [frontend/src/assets/README.md](../frontend/src/assets/README.md)），不是第三方下载。
- `.gitignore` 排除 `.env*`（保留示例）、`runtime/`、`data/`、CSV/PKL、依赖与构建产物。
- 未附带官方 Olist/IEEE-CIS CSV/PKL；许可结论见 [data-sources.md](data-sources.md)，核验日期 2026-11-07，不是法律意见。

## 7. 未验证与阻塞

### MySQL（明确未验收）

- **未执行** V1–V5、`verify_synthetic.sql`；未验证 6+6+4 张表加 `ops_log_event`、约束、索引、外键、JSON、UTC、导入、事务、运行日志游标/保留、并发幂等。
- **V4 对预期结构的干净库、已执行库和缺少对象的部分执行库可重复执行；同名但定义错误的对象不会被自动修复。** 守卫只检查对象名，不比对既有索引/外键/CHECK 的列、引用表、列或定义。
- 未达到 200 `UP`；`storageMode=mysql` 的真实连接/查询/评分/复核/日志未运行。
- `M2MysqlIT` 的 8 个、`M3OpsLogMysqlIT` 的 5 个测试方法均未实际执行；`FOR UPDATE`、FK、CHECK、事务失败回滚（event 超长 `request_id` 场景）、唯一键并发、日志保留清理和 `DuplicateKeyException` 翻译没有被本次执行证明。
- 阻塞原因：无用户提供的独立演示库最小权限账号。端口开放不等于可登录。
- 获得凭据后按 [database/README.md](../database/README.md) 与 [deployment.md](deployment.md) 执行，再更新本文件与当前交接；`docs/acceptance-checklist.md` 是历史 v2 快照，不应直接改写为当前结果。

### 其他

- 未用真实 Olist/IEEE 数据；真实数据规模、内存、耗时、标签分布和效果未知。
- 合成夹具 PR-AUC=1.0 不构成效果声明；未做校准、漂移、公平性、成本或人工复核偏差评估。
- 并发同文件导入竞态、MySQL 唯一键冲突转幂等未验证。
- Olist 时间按无时区解析记 UTC，未做巴西时区换算；仅演示。
- 生产认证、TLS、MFA、限流、会话吊销、密钥管理、审计防篡改、备份恢复演练未实现/未验证。
- JDK 8 + Spring Boot 2.7.18 已结束公开维护；未做 Java/Python 全依赖漏洞扫描。
- 浏览器断言不是完整 WCAG/视觉审计；人工 Ctrl+C 路径未模拟。

## 8. 2026-11-09 M2 增补、corrective patch 与 2026-11-12 最终整改

本节只记录 M2 的实际执行结果；M1 的 33 条 Java 结果是历史 v2 快照。

### 8.1 2026-11-12 实际重跑结果

| 项目 | 实际结果 |
|---|---|
| Java 全量 `mvn test`（14:49，Surefire 报告，不含门控 `M2MysqlIT`） | **58 tests，58 passed，0 failures，0 errors，0 skipped**；ApiContract 14 + M2ConfigApiTest 6 + M2WorkOrderApiTest 14 + M2WorkOrderServiceTest 5 + ModelClient 11 + V2ApiTest 8 |
| 上述各测试类 Surefire 耗时 | 12.368s、4.512s、9.032s、0.253s、0.054s、4.809s（合计约 31.03s，不含 Maven/JVM 启动） |
| Python `.venv\Scripts\python -m pytest model-service -q` | **40 passed**，1 条 Starlette 弃用警告；0.79s |
| Vue production build | **通过**；2063 modules；JS 153.45 kB / gzip 51.74 kB；CSS 23.19 kB / gzip 5.95 kB；2.24s |
| Browser `browser-check.mjs`（同日最终重跑） | **通过**；1440/901/940/980/390 视口；M1 五页回归 + M2 转单/领取/开始/完成/取消确认 + 只读配置预览 + 队列/详情；`pageErrors=[]`；无横向溢出 |
| `mvn -q "-Dtest=M2MysqlIT" test`（14:50） | **1 test，1 skipped，0 failures/errors**；`DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 均未设置，类级条件在 Spring 上下文前跳过；未连接数据库 |
| MySQL V4 / 真实事务 | **未执行、未验证**；无可用演示账号；不能把上面 58 条 Stub 结果当成 MySQL 成功 |

58 项是原 57 项加上本次新增的 writer 旧快照回归测试（`M2WorkOrderServiceTest` 从 4 项变为 5 项），不是把历史 33 项当成当前结果；README 中的 33 项已明确标为历史 v2 基线。

`M2MysqlIT` 源码现在有 8 个 `@Test` 方法（外加 `@BeforeAll` 执行 V4）；本次报告只看到类级跳过，因此 8 个方法均**未实际运行**。其中失败事务测试使用超长 `request_id` 让 event 写入失败，源码存在，但没有真实 MySQL 执行证据。

### 8.2 本次补充补丁覆盖

- 普通 `GET /api/v3/work-orders/{id}` 的 403 契约保持不变；只有重复建单路径对不可见已有工单返回 `404 WORK_ORDER_NOT_FOUND`。
- 新增跨用户重复建单测试：A 领取后，B 同配置得到 404 且无 `alert`/`configSnapshot`/`events`/`resolutionReviewCandidates`/`resolutionReview`/`assigneeId`/`createdBy`/`updatedBy`/`dueAt`/`resolvedAt`；B 用不同配置仍得到 404 而非 409；原处理人和 ADMIN 仍得到 200 `idempotent=true`；可见调用者的不同配置仍为 409。
- 新增重试副作用测试：重复建单后只有 1 个工单、1 条 `CREATED` 事件、1 条 `WORK_ORDER_CREATED` 审计。
- 新增确定性并发测试：同一告警并发 `create` 与 `review`，不使用随机 sleep；复核成功，建单为 201 或 409 `ALERT_NOT_ACTIONABLE`；若建单成功，其 `createdAt` 不晚于复核 `reviewedAt`，且只有 1 个工单/1 个 `CREATED` 事件。
- 新增 `M2WorkOrderServiceTest`（Mockito）：`DuplicateWorkOrderException` 回退不可见 → 404；写入器抛出的**精确** `WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG` 在不可见时 → 404、可见时保留 409；可见 owner 回退 → 幂等结果。未宽泛捕获所有 `ApiException`。
- 修复建单返回详情的旧快照竞态：writer 返回“未分派”旧记录、而 `workOrderStore.findById(id)` 已是“分派给其他 reviewer”时，最终返回 404 而非详情；`createDetail(long id, Authentication)` 通过 ID 重新读取，普通详情 `detail(id)` 仍保持 403。
- Stub 建单与复核共用 `alertMutationLock`（`reviewLock`），覆盖告警读取、幂等、配置检查、插入、`CREATED` 事件和审计边界；MySQL 仍使用 `SELECT ... FOR UPDATE` 和真实事务，没有用 JVM 锁替代 InnoDB。
- Stub 只证明可观察语义，不证明事务回滚或 MySQL 并发；MySQL 专用断言仍属未验证范围。

M2 浏览器实际覆盖：告警详情显式选择配置、创建工单、领取、开始、填写结论完成、工单详情与队列、非 ADMIN 只读配置页及“预览匹配”；ADMIN 取消工单时点击“返回”不发送 `/transitions`，点击“确认取消”只发送一次并进入 `CANCELLED`；901/940/980px 窄桌面与短视口无文档级横向溢出；M1 原有订单筛选/告警复核/质量/模型/健康/抽屉/记住账号断言继续通过。

M2 corrective patch 既有测试继续覆盖：同配置幂等优先于“配置已停用/告警已解决”；`alertWorkOrders` 能查到第 100 条之后的旧工单；详情 `actions` 按角色/处理人/终态计算；两个并发建单只产生一个新建和一个幂等；M2 匿名 401、缺失 CSRF 403。`M2MysqlIT` 源码已扩展为自包含 fixture、V4 执行两次、精确索引/外键/CHECK 预检、违规版本三元组/非法状态/跨告警复核、唯一键翻译、两个建单线程、建单/复核并发、按 `CreateResult.isIdempotent()` 返回 200/201，以及超长 `request_id` 触发 event 写入失败后的回滚断言；但**8 个方法本次均未运行**，不能据此宣称 MySQL 通过。

`docs/acceptance-checklist.md` 是历史 v2 验收快照（仍写 Java 33 项，且没有 M2/V4 章节），不是当前 M2 验收口径，未改写该历史文件；当前结果以本文和 [handoff-latest.md](handoff-latest.md) 为准。

M2 新增 API 契约见 [api-contract-v3-work-orders.md](api-contract-v3-work-orders.md)。截图新增 `runtime/ui-v3-work-order-detail-1440.png`、`runtime/ui-v3-work-order-cancel-1440.png`、`runtime/ui-v3-work-order-configs-1440.png`、`runtime/ui-v3-work-orders-1440.png`、`ui-v3-work-orders-901/940/980.png` 及对应 390 版本；仍不声称人工视觉审阅。

## 9. 2026-11-12 M3 结构化运行日志

### 9.1 实际结果

| 项目 | 实际结果 |
|---|---|
| Java 全量 `mvn test`（10:02，不含门控 MySQL IT） | **74 tests，74 passed，0 failures，0 errors，0 skipped**；M2 58 + M3OpsLogApiTest 6 + M3OpsLogServiceTest 8 + M3OpsLogResilienceTest 2 |
| 各测试类耗时 | ApiContract 14.607s、M2Config 5.615s、M2WorkOrderApi 12.778s、M2WorkOrderService 0.157s、M3OpsLogApi 4.106s、M3OpsLogResilience 1.338s、M3OpsLogService 0.004s、ModelClient 0.056s、V2 5.645s |
| Python `.venv\Scripts\python -m pytest model-service -q` | **40 passed**，1 条 Starlette 弃用警告；上一轮 0.89s；本轮未改 Python |
| Vue production build | **通过**；2064 modules；JS 163.72 kB / gzip 54.69 kB；CSS 26.20 kB / gzip 6.53 kB；2.27s（仅后端/测试/文档改动后的构建） |
| Browser `browser-check.mjs` | **未在本轮重跑**；上一轮通过记录仍保留，不能声称本轮重新通过浏览器验收 |
| `M2MysqlIT` | **1 test，1 skipped**；8 个方法仍未在真实 MySQL 执行 |
| `M3OpsLogMysqlIT`（10:03） | **1 test，1 skipped，0 failures/errors**；三个 DB 变量均未设置，未连接数据库；5 个测试方法未实际执行，canary 未实测 |
| MySQL V5 / 真实日志 | **未执行、未验证**；游标、保留清理、安全字段/canary、事务行为不能据 Stub 声称通过 |

### 9.2 M3 覆盖

- V5 只新增 `ops_log_event`，独立于 `audit_event`；Stub 有界 30 天/50,000 条。
- GET 日志/健康仅 ADMIN；默认 `minLevel=WARN`；ADMIN 最低 INFO；DEBUG 不产生、不可查询；非法 cursor/时间范围/limit 返回 400。
- 客户端上报仅固定字段；level/摘要服务端映射；`relatedRequestId` 仅 UUID 格式校验、精确查询；actor 取 Session；20 次/分钟单实例内存限流；401 不上报；未知字段/禁止 code/非法 route 400。
- 限流窗口改为有界 4,096 表；只清理过期窗口，不淘汰有效窗口；容量满且无过期项时新窗口 429；20/21 次、过期回收、容量拒绝测试通过。
- 日志写入 REQUIRES_NEW + 捕获 RuntimeException；业务成功不受影响；不捕获 JVM Error；ops 接口不递归写日志；健康 DEGRADED 和限频兜底告警。
- `OpsLogHealth` 初始 DEGRADED、探测成功 UP、写入失败 DEGRADED、成功恢复 UP、探测失败 DEGRADED 均已有确定性单测。
- 业务事件成功在事务提交/调用返回后记录；失败不产生成功事件；配置校验失败/发布失败、工单拒绝均无 `*_COMPLETED`。
- 专用保留任务分批 1,000、每次最多 10 批；不在业务请求路径做全表计数。
- 前端只 ADMIN 浮窗，打开且未暂停每 5 秒轮询；关闭/暂停停止；无服务器删除。

### 9.3 文档口径

`docs/acceptance-checklist.md` 仍是历史 v2 快照（Java 33、无 M2/M3/V4/V5 章节），本次未改写；当前 M3 验收以本文和 [handoff-latest.md](handoff-latest.md) 为准。

### 9.4 未复现的测试抖动

一次中间全量运行在 `M3OpsLogApiTest.configValidationAndPublishFailuresDoNotLeaveCompletedEvents` 的成功发布事件断言上失败；同一方法单独重跑、随后两次全量均通过，未再复现。本轮又在无 sleep、无随机延迟的情况下精确重跑该方法 10 次，10/10 通过；并增加成功发布后的 `/api/v3/ops/health` `UP` 与 `writeFailureCount=0` 诊断断言。根因仍未定位，此次未复现；该现象可能与本地 dev 进程共享 `target/classes` 或上下文时序有关，但没有证据证明。不能声称已修复或绝对无偶发失败。
