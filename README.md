# 电商订单轻量数仓 · 欺诈交易异常检测演示

本科毕设规模的 **Vue 3 + Spring Boot + FastAPI + MySQL 8.0** 最小项目。v2 在 v1 契约上增量实现：订单概览、异常队列、详情与人工复核、数据质量、模型评估五个页面；会话登录、角色权限、CSRF、审计；Olist/IEEE-CIS 分源导入、幂等批次与数据质量；IEEE-CIS 独立训练/评测。M2 增加风险告警工单与配置生命周期；M3 增加 ADMIN 结构化运行日志查询和前端浮窗；M4 增加 ADMIN 效能总览与 SVG 折线/环图/柱状图。**不是完整业务系统，不是大规模分布式数仓，不包含论文。**

> 全部运行数据为虚构或按来源隔离的本地演示数据。规则分不是真实欺诈概率，告警不是欺诈事实，复核结论不是客观真值，页面掩码不是加密。Olist 没有直接欺诈标签；IEEE-CIS 的 `isFraud` 只在其自身交易域有效；两者不得拼接或互相补标。

## 1. 目录结构

```text
beysesji/
├── frontend/                      # Vue 3 / JavaScript / CSS / Vite
│   ├── src/App.vue                # 登录、导航、哈希路由与权限态
│   ├── src/api.js                 # 相对路径 API、CSRF、错误处理
│   ├── src/pages/                 # 登录/订单/告警/详情复核/质量/模型/健康
│   ├── src/components/            # M3 运行日志浮窗、M4 SVG 图表
│   ├── src/style.css
│   └── vite.config.js             # 127.0.0.1:5173，/api 代理到 8080
├── backend/                       # Spring Boot 对外 API、事务、审计、导入协调
│   ├── src/main/java/com/example/fraud/
│   ├── src/main/resources/application.yml
│   ├── src/test/java/             # 101 项 MockMvc/服务/模型客户端测试
│   └── pom.xml
├── model-service/                 # FastAPI v1 规则评分 + v2 IEEE 训练/评测
│   ├── app/{main,schemas,scoring,training}.py
│   ├── tests/                     # 40 项 v1+v2 测试
│   └── requirements*.txt
├── database/
│   ├── migrations/V1__init.sql
│   ├── migrations/V3__v2_sources_eval.sql
│   ├── migrations/V4__v3_work_orders.sql
│   ├── migrations/V5__v3_ops_events.sql
│   ├── seed/V2__synthetic_demo.sql
│   ├── verify_synthetic.sql
│   └── README.md
├── docs/
│   ├── architecture.md            # 架构、边界、v2 数据流
│   ├── api-contract.md            # 冻结的 v1 契约
│   ├── api-contract-v2.md         # 认证/导入/质量/审计/模型增量契约
│   ├── api-contract-v3-ops-logs.md # M3 运行日志与客户端错误契约
│   ├── api-contract-v3-dashboard.md # M4 ADMIN 聚合看板契约
│   ├── data-dictionary.md         # ODS/DWD/DWS/ADS 与 v2 表
│   ├── data-sources.md            # 来源、许可、核验日期、限制
│   ├── privacy-and-security.md    # 虚构数据、掩码、密钥、日志
│   ├── deployment.md              # 本地部署、演示账号、恢复与残余风险
│   ├── open-issues.md
│   ├── acceptance-checklist.md
│   ├── verification.md            # 实际命令、结果与未验证范围
│   └── premise-review.md          # 前提审查与 v1/v2 冲突决策
├── scripts/dev.ps1                # 启动三端并等待健康；可显式 MySQL
├── scripts/verify.ps1             # Python、Java、Vue 构建
├── scripts/browser-check.mjs      # 可选登录/五页/移动端断言
├── data/external/                 # 本地数据，Git 忽略，默认不存在
├── runtime/                       # 模型产物/日志/临时浏览器工具，Git 忽略
├── .env.example                   # 占位配置，无真实秘密
└── .gitignore
```

## 2. 调用关系与边界

```text
Vue 3 → Spring Boot → MySQL（订单/特征/评分/告警/复核/导入/审计/模型/M3 运行日志/M4 聚合看板）
                  └──→ FastAPI（v1 规则健康/评分；v2 IEEE 训练/评测）
```

- Vue 不直连 Python 或 MySQL；Python 不访问 MySQL、不处理复核事务。
- 单库 `fraud_demo`，`ods_`/`dwd_`/`dws_`/`ads_` 仅为逻辑分层。
- Olist 订单进入 `dwd_order`；IEEE-CIS 只进入 `eval_ieee_transaction` 独立评测域，禁止与订单表 JOIN。
- 订单表没有 `is_fraud` 字段；人工复核不自动成为训练标签。

详见 [架构说明](docs/architecture.md)、[数据字典](docs/data-dictionary.md)、[v2 契约](docs/api-contract-v2.md)。

## 3. 环境要求

- Windows PowerShell 5.1+ 或 PowerShell 7（脚本保持纯 ASCII）。
- JDK 8 与 Maven 3.9：本机兼容方案为 Spring Boot 2.7.18；其公开维护已结束，长期应迁移 JDK 17 + 受支持的 Spring Boot 3 并重测。
- Python 3.12。
- Node.js 20.19+ 或 22.12+（本机 Node.js 24 已构建通过）。
- MySQL 8.0（仅 MySQL 模式；V3 的 `DROP CHECK` 需 8.0.16+）。
- 不使用 Docker、Kafka、Hadoop、Kubernetes。端口 8000、8080、5173 必须空闲。

## 4. 启动 A：内存契约演示（无需数据库密码）

用于验证三端、认证、五页、导入/训练协调与 API；**不是 MySQL 验证**。健康固定 `DEGRADED`，`storageMode=stub`，数据库 `BYPASS`；重启后导入/模型/复核修改不保留（磁盘模型产物保留）。

```powershell
# 1. Python 虚拟环境与依赖（首次）
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --index-url https://pypi.org/simple -r model-service\requirements-dev.txt

# 2. 前端依赖（首次）
npm --prefix frontend ci

# 3. 启动三端；Ctrl+C 停止本脚本启动的进程
.\scripts\dev.ps1
```

如执行策略阻止本地脚本，只在当前进程运行 `Set-ExecutionPolicy -Scope Process Bypass`，不要关闭系统安全策略。

| 入口 | 地址 |
|---|---|
| Vue 页面 | `http://127.0.0.1:5173` |
| Java 聚合健康 | `http://127.0.0.1:8080/api/v1/health` |
| FastAPI 健康/文档 | `http://127.0.0.1:8000/health`、`/docs` |

### 4.1 虚构演示账号

| 角色 | 用户名 | 默认密码 | 权限 |
|---|---|---|---|
| 分析员 | `demo_analyst` | `Analyst-Demo-2026!` | 查看业务页面 |
| 复核员 | `demo_reviewer` | `Reviewer-Demo-2026!` | 查看 + 提交复核 |
| 管理员 | `demo_admin` | `Admin-Demo-2026!` | 复核 + 导入/训练 + 审计 |

这些是**虚构演示账号**，不是生产认证。部署时必须通过 `APP_DEMO_ANALYST_PASSWORD`、`APP_DEMO_REVIEWER_PASSWORD`、`APP_DEMO_ADMIN_PASSWORD` 覆盖默认值；不要把这些值用于任何真实系统。

### 4.2 本地数据放置

数据默认不随仓库提供。把文件放到：

```text
data/external/olist/olist_orders_dataset.csv
data/external/olist/olist_order_payments_dataset.csv
data/external/olist/olist_customers_dataset.csv   # 可选
data/external/ieee-cis/train_transaction.csv
```

缺失时导入/训练返回受控 `400 IMPORT_SOURCE_NOT_FOUND`，页面显示空状态，不伪造批次或模型。Olist 文件受 CC BY-NC-SA 4.0 约束；IEEE-CIS 受竞赛规则约束，原始/行级数据不随仓库再分发。

### 4.3 操作顺序

1. 用管理员登录 → **数据质量** → 导入 Olist / IEEE-CIS（相同文件重复导入会返回 `idempotent=true`）。
2. 用管理员登录 → **模型评估** → 输入版本（如 `ieee-logreg-v1`）→ 训练并评测。训练按时间 70/15/15 并以 `card1+addr1` 清除跨段实体；清除后某段为空会失败，不静默回填。
3. **订单概览** 查看 Olist 订单（`dataOrigin=OLIST`，金额 BRL）；Olist 订单没有账户年龄/24h 频次，不伪造 DWS 特征，因此不自动可评分。
4. **异常队列** → 详情 → 复核员提交结论；版本冲突/已解决返回 409。

### 4.4 分终端启动（排错）

```powershell
# 终端 1：FastAPI（从 model-service 目录启动，服务内以源码位置定位项目根）
Push-Location model-service; ..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000; Pop-Location

# 终端 2：Spring Boot（从 backend 启动；导入/产物路径有项目根回退）
Push-Location backend; mvn spring-boot:run; Pop-Location

# 终端 3：Vue
npm --prefix frontend run dev
```

## 5. 启动 B：MySQL 8.0

### 5.1 初始化顺序

在**独立空白演示库** `fraud_demo` 中按顺序执行：

```text
database/migrations/V1__init.sql      # 建库、6 张 v1 表
database/seed/V2__synthetic_demo.sql  # 3 笔虚构订单/评分/告警；非幂等，只执行一次
database/migrations/V3__v2_sources_eval.sql  # 放开单源限制，新增导入/DQ/审计/模型/评测表
database/migrations/V4__v3_work_orders.sql   # M2 工单/配置/版本/事件与组合约束
database/migrations/V5__v3_ops_events.sql    # M3 运行诊断日志；独立于 audit_event
```

然后用 `database/verify_synthetic.sql` 核对基础行数。V3 必须在 V1 之后，V4 必须在 V1–V3 之后，V5 必须在 V4 之后；V2 种子在 V3 前后均可，但推荐按上表顺序。不要重复执行种子。V4 的约束/索引使用 `information_schema` 守卫：**V4 对预期结构的干净库、已执行库和缺少对象的部分执行库可重复执行；同名但定义错误的对象不会被自动修复。** 不要用 `DROP TABLE` 或回滚迁移修复。详见 [database/README.md](database/README.md)。

M3 必须先应用 V5 再启用日志写入；V5 不存在或 `ops_log_event` 不可用时，业务继续运行，`GET /api/v3/ops/health` 返回 `DEGRADED`，前端显示日志降级而不伪造日志。日志保留由专用任务按 30 天/50,000 条软上限清理。

MySQL 管理账号只做 DDL；应用另建最小权限账号，仅授予 `fraud_demo.*` 的 SELECT/INSERT/UPDATE/DELETE：

```sql
CREATE USER 'fraud_demo_app'@'127.0.0.1' IDENTIFIED BY 'replace-with-local-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON fraud_demo.* TO 'fraud_demo_app'@'127.0.0.1';
```

### 5.2 仅当前进程注入凭据

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/fraud_demo?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8'
$env:DB_USERNAME = 'fraud_demo_app'
$securePassword = Read-Host '本地演示数据库密码' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
try {
  .\scripts\dev.ps1 -WithMySql
} finally {
  Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}
```

MySQL 与模型均正常时健康才返回 200 `UP`。本机当前**没有**可用演示账号；M4 隔离容器已验证 V1、V3、V4、V5 的 DDL 与看板聚合，但 V2 种子、M2/M3 的真实 CRUD、事务、并发幂等、运行日志清理及生产 200 `UP` **仍未验证**。

## 6. API 与最小调用示例

完整 v1 见 [api-contract.md](docs/api-contract.md)，v2 见 [api-contract-v2.md](docs/api-contract-v2.md)。

| 方法 | 路径 | 作用 | 权限 |
|---|---|---|---|
| GET | `/api/v1/health` | 聚合 MySQL/模型健康 | 匿名 |
| POST | `/api/v2/auth/login` | 会话登录 | 匿名 |
| GET | `/api/v2/auth/csrf` | CSRF 令牌 | 匿名 |
| GET | `/api/v2/auth/me` | 当前用户/角色 | 登录 |
| GET | `/api/v1/orders?page=0&size=20&status=PAID&keyword=DEMO` | 订单查询 | 登录 |
| GET | `/api/v1/alerts?page=0&size=20&alertStatus=OPEN&riskLevel=HIGH` | 告警队列 | 登录 |
| GET | `/api/v1/alerts/1` | 告警/订单/复核历史 | 登录 |
| POST | `/api/v1/alerts/1/reviews` | 复核（session 身份 + CSRF） | REVIEWER/ADMIN |
| POST | `/api/v1/orders/DEMO-O-0002/score` | 读取 DWS 快照评分 | 登录 |
| POST | `/api/v2/imports` | 导入 Olist/IEEE-CIS | ADMIN |
| GET | `/api/v2/data-quality/summary` | 最近批次质量 | 登录 |
| GET | `/api/v2/audit` | 追加式审计 | ADMIN |
| GET | `/api/v2/models` | 模型注册 | 登录 |
| POST | `/api/v2/models/train` | IEEE 训练/评测 | ADMIN |
| GET | `/api/v2/evaluations` | 评测指标 | 登录 |
| GET | `/api/v2/ieee/summary` | 独立评测域统计 | 登录 |
| GET | `/api/v3/dashboard/overview?from=&to=&bucket=week` | ADMIN 效能总览聚合（UTC，最大 366 天） | ADMIN |
| GET | `/api/v3/ops/logs?minLevel=WARN&limit=50` | 结构化运行日志，游标分页 | ADMIN |
| GET | `/api/v3/ops/health` | 日志存储进程级健康/降级信号 | ADMIN |
| POST | `/api/v3/ops/client-errors` | 固定字段客户端 API 失败上报，单实例限流 | 登录 + CSRF |

M3 运行日志入口仅 ADMIN 可见，位于右下角；打开且未暂停时每 5 秒轮询，默认 WARN，可查看 INFO 及以上，支持暂停、加载更早、清屏当前视图和移动端。日志只按文本渲染，不读取任意服务器文件，不提供服务器日志删除。

M4 效能总览仅 ADMIN 可见，使用原生 SVG/CSS 折线、环图和柱状图，不引入图表依赖；订单按 OLIST/SYNTHETIC 分开且不汇总金额，IEEE-CIS 评测不进入该接口。当前范围无数据时显示真实空态，当前状态快照单独标注 `asOf`。详见 [api-contract-v3-dashboard.md](docs/api-contract-v3-dashboard.md)。

PowerShell 登录 + CSRF 示例（不要把密码写入脚本或历史）：

```powershell
$base = 'http://127.0.0.1:8080'
$body = @{ username = 'demo_reviewer'; password = 'Reviewer-Demo-2026!' } | ConvertTo-Json
Invoke-RestMethod "$base/api/v2/auth/login" -Method Post -ContentType 'application/json' -Body $body -SessionVariable session
$csrf = Invoke-RestMethod "$base/api/v2/auth/csrf" -WebSession $session
$headers = @{ $csrf.headerName = $csrf.token }

Invoke-RestMethod "$base/api/v1/orders?page=0&size=20" -WebSession $session
$review = @{ reviewResult = 'INCONCLUSIVE'; comment = '虚构演示复核'; expectedVersion = 1 } | ConvertTo-Json
Invoke-RestMethod "$base/api/v1/alerts/1/reviews" -Method Post -WebSession $session `
  -Headers $headers -ContentType 'application/json' -Body $review
```

健康在 stub 模式返回 503/DEGRADED，不是页面故障。固定规则：基础 0.05；金额 ≥1000 +0.45；支付尝试 ≥3 +0.25；账户年龄 ≤7 天 +0.20；24h 交易 ≥5 +0.10；上限 0.99。`<0.40` LOW，`[0.40,0.70)` MEDIUM，`≥0.70` HIGH。

## 7. 验证

```powershell
.\scripts\verify.ps1
```

运行前停止 `dev.ps1` 启动的后端，避免 Maven 与运行进程共享 `backend/target/classes`。脚本执行 Python 40 项、Java 101 项、Vue 生产构建；不把 MySQL DDL/凭据验证自动算通过。

浏览器验收（可选，不进入生产依赖）：

```powershell
npm install --prefix runtime\browser-tools playwright-core@1.55.0 --no-audit --no-fund
$env:PLAYWRIGHT_MODULE = "$PWD\runtime\browser-tools\node_modules\playwright-core"
node scripts\browser-check.mjs
```

当前实测（2026-11-18）：Python 40 passed 是**历史结果，本轮未重跑**；Java 全量 **101 passed**（0 failures/errors/skipped；不含 `*IT`）、Vue build 通过、M4 浏览器验收通过（1440/390，`pageErrors=[]`；前端构建/浏览器为此前记录，本轮未重跑）。`M4DashboardMysqlIT` 已在一次性 Testcontainers MySQL 8.0.36 容器中执行 **4 tests，4 passed，0 skipped**；M2/M3 MySQL IT 仍为 **1 skipped**，未执行、未验证。M4 MySQL 使用 V1、V3、V4、V5，不加载 V2 种子；常规 101 项不包含这 4 项。此前 M3 阶段为 74 项和更早的 70 项，**均为历史结果，不是当前实测**。详见 [verification.md](docs/verification.md)。

## 8. 来源许可与安全

- [数据来源与许可](docs/data-sources.md)：核验日期 **2026-11-07**。Olist 为 CC BY-NC-SA 4.0，无直接真实欺诈标签；IEEE-CIS 有 `isFraud` 但不是完整订单明细，公开再分发/商业使用受竞赛规则限制；Handbook 代码 GPL-3.0、正文/图片 CC BY-SA 4.0，独立 PKL 无许可证阻塞。
- 本仓库不附带外部 CSV/PKL。使用者需自行从官方来源获取并遵守许可；IEEE-CIS 原始/行级数据不得公开再分发，商业使用需书面授权。许可核验不是法律意见。
- [隐私与安全](docs/privacy-and-security.md)：仅虚构/匿名演示；展示掩码不等于加密；确需敏感字段时才引入应用层 AES-GCM 与仓库外密钥；日志不得输出明文、密码、Cookie 或原始载荷。
- 当前认证是**演示会话认证**，不是生产 IAM；CSRF 仅覆盖浏览器 Cookie 会话，不覆盖未来 API Token 场景。
- 公开云、付费资源、域名或公网暴露需先列出成本与安全风险并获得明确同意；本仓库默认只做本地/私有部署。

## 9. 契约冻结与变更控制（交给后续开发者/模型）

以下契约已冻结，是事实基线。任何后续开发（包括 AI 助手）**不得静默重构**；确需改动时必须先提出并经确认。

### 9.1 冻结范围

| 类别 | 事实来源 | 冻结内容 |
|---|---|---|
| v1 接口 | [api-contract.md](docs/api-contract.md) | 路径、字段、错误码、分页、201+Location、409、健康语义（stub 503/DEGRADED/BYPASS） |
| v2 接口 | [api-contract-v2.md](docs/api-contract-v2.md) | 认证/CSRF、角色、导入幂等、质量、审计、模型注册/评测字段 |
| 数据契约 | [data-dictionary.md](docs/data-dictionary.md) | 表/字段、金额上界、特征版本、评分/告警/复核语义、v2 独立评测域 |
| 标签语义 | [data-dictionary.md](docs/data-dictionary.md) §5–6 | 分数≠真实概率；告警≠欺诈事实；复核≠客观真值；Olist 无监督标签；IEEE 标签不外推 |
| 来源许可 | [data-sources.md](docs/data-sources.md) | 许可结论、阻塞、禁止跨源拼接/补标 |
| 隐私安全 | [privacy-and-security.md](docs/privacy-and-security.md) | 仅虚构、掩码≠加密、日志禁明文、密钥仓库外、AES-GCM 未启用 |

### 9.2 变更流程

修改 9.1 任一项前必须停下提案，包含：改动项、原因、影响文件/接口/表/测试与兼容性、对标签/许可/隐私的风险。确认后才实施，并同步契约、测试与 [verification.md](docs/verification.md)。未走流程的接口或标签变更视为不合规。

### 9.3 当前基线

Python **40** 项为历史结果、本轮未重跑；Java **101** 项测试通过；Vue 构建通过；M4 浏览器验收通过（1440/390，`pageErrors=[]`；前端构建/浏览器为此前记录，本轮未重跑）。Java 的 **33** 项是历史 v2 基线（ApiContract 14 + ModelClient 11 + V2 8），**74** 和 **70** 项是此前 M3 阶段历史结果，均不是当前实测。MySQL 仅 **M4 隔离 Testcontainers 环境**已验证 V1、V3、V4、V5；V2 种子、M2/M3 MySQL IT、生产数据、查询计划和性能 **未验证**。外部真实 Olist/IEEE 数据未导入，端到端只用合成烟测文件。[acceptance-checklist.md](docs/acceptance-checklist.md) 是历史 v2 验收快照（仍写 33 项），不作为当前 M4 验收口径；当前结果见 [verification.md](docs/verification.md)。
