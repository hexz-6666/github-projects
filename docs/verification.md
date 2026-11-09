# 实际验证记录

**验证日期：2026-11-09（Asia/Shanghai）。** 本文件记录本机实际执行的结果，不代表生产验收，也不把 stub 当作 MySQL 成功。除特别说明外，运行数据为独立虚构/合成夹具；未下载官方 Olist/IEEE-CIS 数据。2026-11-09 增补 v3 M1 登录页、记住账号、壳层与移动端抽屉收尾验收。

## 1. 环境与范围

- Windows；JDK 8（1.8.0_451）、Maven 3.9.11、Python 3.12.9、Node.js 24.7.0。
- MySQL 8.0 服务与 3306 端口存在，但**没有可用演示账号**，未猜密码。
- 无 Docker/Kafka/Hadoop/Kubernetes；无生产 IAM、TLS、KMS、备份恢复实战。
- v2 代码已实现五页、会话认证/RBAC/CSRF、导入/质量/审计、IEEE 训练评测；v1 契约保持。
- v3 M1 已实现登录页/记住账号/紧凑壳层和现有五页适配；M1 未新增后端、API、数据库迁移、工单、日志中心、看板或 ECharts。**2026-11-09 增补 M2：工单/配置 API、V4（仅静态）、工单前端与浏览器流程；M3 尚未开始。**

## 2. 自动化构建与测试

最终命令：

```powershell
.\scripts\verify.ps1
```

结果 **通过（退出码 0）**：

| 项目 | 实际结果 | 范围 |
|---|---|---|
| Python pytest | **40 passed**，1 条 Starlette `BlockingPortal` 弃用警告 | v1 32 + v2 训练/评测 8 |
| Java Maven test（M1 当时） | **33 passed，0 failures/errors** | ApiContract 14 + ModelClient 11 + V2ApiTest 8；M2 后最新为 45 passed，见 §8 |
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

- **未执行** V1/V2/V3、`verify_synthetic.sql`；未验证 6+6 张表、约束、索引、外键、JSON、UTC、导入、事务、并发幂等。
- 未达到 200 `UP`；`storageMode=mysql` 的真实连接/查询/评分/复核未运行。
- 阻塞原因：无用户提供的独立演示库最小权限账号。端口开放不等于可登录。
- 获得凭据后按 [database/README.md](../database/README.md) 与 [deployment.md](deployment.md) 执行，再更新本文件与验收清单。

### 其他

- 未用真实 Olist/IEEE 数据；真实数据规模、内存、耗时、标签分布和效果未知。
- 合成夹具 PR-AUC=1.0 不构成效果声明；未做校准、漂移、公平性、成本或人工复核偏差评估。
- 并发同文件导入竞态、MySQL 唯一键冲突转幂等未验证。
- Olist 时间按无时区解析记 UTC，未做巴西时区换算；仅演示。
- 生产认证、TLS、MFA、限流、会话吊销、密钥管理、审计防篡改、备份恢复演练未实现/未验证。
- JDK 8 + Spring Boot 2.7.18 已结束公开维护；未做 Java/Python 全依赖漏洞扫描。
- 浏览器断言不是完整 WCAG/视觉审计；人工 Ctrl+C 路径未模拟。

## 8. 2026-11-09 M2 增补

本节只记录 M2 的实际执行结果；M1 的 33 条 Java 结果是当时快照。

| 项目 | 实际结果 |
|---|---|
| Java 全量 `mvn test` | **45 passed，0 failures/errors，0 skipped**；包含 M2ConfigApiTest、M2WorkOrderApiTest |
| Python `pytest model-service -q` | **40 passed**，1 条 Starlette 弃用警告 |
| Vue production build | **通过**；2063 modules；JS 152.63 kB / gzip 51.55 kB；CSS 22.57 kB / gzip 5.82 kB |
| Browser `browser-check.mjs` | **通过**；1440/390 的 M1 五页回归 + M2 转单/领取/开始/完成 + 只读配置预览 + 队列/详情；`pageErrors=[]`；无横向溢出 |
| MySQL V4 | **未执行、未验证**；无可用演示账号，只做静态审查 |

M2 浏览器实际覆盖：告警详情显式选择配置、创建工单、领取、开始、填写结论完成、工单详情与队列、非 ADMIN 只读配置页及“预览匹配”；M1 原有订单筛选/告警复核/质量/模型/健康/抽屉/记住账号断言继续通过。

M2 新增 API 契约见 [api-contract-v3-work-orders.md](api-contract-v3-work-orders.md)。截图新增 `runtime/ui-v3-work-order-detail-1440.png`、`runtime/ui-v3-work-order-configs-1440.png`、`runtime/ui-v3-work-orders-1440.png` 及对应 390 版本；仍不声称人工视觉审阅。
