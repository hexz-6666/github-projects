# 实际验证记录

**验证日期：2026-11-07（Asia/Shanghai）。** 本文件记录本机实际执行的结果，不代表生产验收，也不把 stub 当作 MySQL 成功。除特别说明外，运行数据为独立虚构/合成夹具；未下载官方 Olist/IEEE-CIS 数据。

## 1. 环境与范围

- Windows；JDK 8（1.8.0_451）、Maven 3.9.11、Python 3.12.9、Node.js 24.7.0。
- MySQL 8.0 服务与 3306 端口存在，但**没有可用演示账号**，未猜密码。
- 无 Docker/Kafka/Hadoop/Kubernetes；无生产 IAM、TLS、KMS、备份恢复实战。
- v2 代码已实现五页、会话认证/RBAC/CSRF、导入/质量/审计、IEEE 训练评测；v1 契约保持。

## 2. 自动化构建与测试

最终命令：

```powershell
.\scripts\verify.ps1
```

结果 **通过（退出码 0）**：

| 项目 | 实际结果 | 范围 |
|---|---|---|
| Python pytest | **40 passed**，1 条 Starlette `BlockingPortal` 弃用警告 | v1 32 + v2 训练/评测 8 |
| Java Maven test | **33 passed，0 failures/errors** | ApiContract 14 + ModelClient 11 + V2ApiTest 8 |
| Vue production build | **通过**，18 modules，JS 99.97 kB / gzip 37.32 kB | Vue 3.5.45 / Vite 6.4.5 |
| `npm audit --audit-level=moderate` | **0 vulnerabilities** | 仅前端锁定依赖，不等于 Java/Python 全依赖无漏洞 |
| `pip check` | No broken requirements found | 依赖一致性，不是漏洞扫描 |

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

通过断言（有批次/模型与空状态各跑一次）：

- 登录页可见；点击“复核员”演示账号可登录。
- 订单概览加载 3 笔虚构订单；订单表格不出现“已确认欺诈”。
- 异常队列 → 详情 → 提交复核 → 成功提示；身份/CSRF 生效。
- 数据质量：空状态显示“尚无导入批次”；有批次时显示“最近批次/检查结果”。
- 模型评估：空状态显示“尚无训练模型”；有模型时显示“已注册模型”和指标表。
- 系统健康：显示 `DEGRADED`、`stub`、`database=BYPASS`，明确“不代表 MySQL 连接成功”。
- 390×844 移动视口无横向溢出。
- 新匿名会话回到登录页。
- 页面 `pageerror` 为空。

截图位于 `runtime/ui-*.png`（本机忽略目录）。**模型不支持读图，因此没有声称人工审阅截图的视觉美观性或完整可访问性**；以上仅为 DOM/布局/错误断言。

## 5. 清理

- 最终验证后停止 `dev.ps1` 启动的三端，确认 8000/8080/5173 无监听。
- 删除 `data/external/olist`、`data/external/ieee-cis` 和 `runtime/models` 中的烟测文件；仓库/工作区不保留外部数据或模型产物。
- 实体清除的独立单测在 Python 中覆盖：重复 `card1+addr1` 从验证/测试段被移除，丢弃计数 20/30，三段仍严格时间递增。
- 未使用按端口/名称的全局强杀，未触碰 DeepSeek Harness GUI。

## 6. 隐私、许可与仓库检查

- 源码扫描未发现请求正文、SQL 参数、密码、Cookie、邮箱或原始载荷的业务日志；演示密码只出现在明确的虚构账号配置/文档中。
- 错误响应不回显非法输入、下游正文、堆栈、SQL 或内部 URL。
- 前端只请求相对 `/api`；无 `v-html`、外部图片/字体；CSRF 令牌不写入 localStorage。
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
