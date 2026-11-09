# 本地部署、账号与恢复

本文只覆盖**本机/私有演示**。不包含公网、云、域名、TLS 终止、容器编排或生产高可用；这些需要单独的成本与安全评审并获明确批准。

## 1. 运行模式

| 模式 | 启动 | 数据库 | 健康 | 适用 |
|---|---|---|---|---|
| v1 内存契约 | `.\scripts\dev.ps1` | `BYPASS` | 503 `DEGRADED` | 五页/认证/导入/训练协调联调；不验证 MySQL |
| MySQL | `.\scripts\dev.ps1 -WithMySql` | 真实连接 | 依赖正常时 200 `UP` | 需先执行 V1/V2/V3 并提供账号 |

stub 模式重启后内存中的导入批次、模型注册、审计、评分/复核修改会丢失；磁盘 `runtime/models/*.json` 仍保留，但数据库中没有对应注册记录。

## 2. 端口与地址

| 服务 | 默认地址 | 绑定 |
|---|---|---|
| Vue/Vite | `http://127.0.0.1:5173` | 仅 loopback |
| Spring Boot | `http://127.0.0.1:8080` | `server.address=127.0.0.1` |
| FastAPI | `http://127.0.0.1:8000` | 启动命令显式 `--host 127.0.0.1` |

端口冲突时先停止占用进程；不要用按名称的全局强杀，也不要动 DeepSeek Harness GUI。

## 3. 配置项

| 变量/属性 | 默认 | 说明 |
|---|---|---|
| `DB_URL` | 无 | MySQL JDBC URL；MySQL 模式必填 |
| `DB_USERNAME` / `DB_PASSWORD` | 无 | 最小权限应用账号；只注入当前进程 |
| `MODEL_BASE_URL` | `http://127.0.0.1:8000` | Java → Python；只允许 loopback HTTP(S) origin |
| `app.import-root` / `IMPORT_ROOT` | `data/external` | Olist/IEEE 本地目录；服务内兼容从 `backend/` 启动的项目根回退 |
| `MODEL_DATA_ROOT` | 项目根 `data/external` | Python 允许读取的训练根；路径越界返回 400 |
| `MODEL_ARTIFACT_ROOT` | 项目根 `runtime/models` | 模型产物根；Java 只接受 `runtime/models/...` 且文件必须存在 |
| `APP_DEMO_ANALYST_PASSWORD` | `Analyst-Demo-2026!` | 演示账号密码；生产禁用默认值 |
| `APP_DEMO_REVIEWER_PASSWORD` | `Reviewer-Demo-2026!` | 同上 |
| `APP_DEMO_ADMIN_PASSWORD` | `Admin-Demo-2026!` | 同上 |
| `VITE_API_TARGET` | `http://127.0.0.1:8080` | Vite `/api` 代理目标 |

Spring 的 `APP_DEMO_*` 是环境变量到 `app.demo.*` 的宽松绑定；也可用 `--app.demo.admin-password=...`，但不要写入仓库或截图。数据目录与模型产物在 `.gitignore` 中。

## 4. 演示账号与权限

| 用户名 | 角色 | 能力 |
|---|---|---|
| `demo_analyst` | ANALYST | 查看订单/告警/详情/质量/模型/健康 |
| `demo_reviewer` | REVIEWER | 上述 + 提交复核 |
| `demo_admin` | ADMIN | 上述 + 导入/训练/审计 |

账号存于内存 `UserDetailsService`，密码 BCrypt 编码；不是生产 IAM，不支持注册、改密、MFA、会话吊销或集中身份。登录失败统一 401，不泄露用户是否存在。

## 5. 首次部署步骤

1. 安装 JDK 8、Maven 3.9、Python 3.12、Node 20+/22+；确认 8000/8080/5173 空闲。
2. 创建 `.venv`，安装 `model-service/requirements-dev.txt`；`npm --prefix frontend ci`。
3. stub 验证：`.\scripts\dev.ps1`，浏览器登录 `demo_admin`，确认健康页显示 `DEGRADED`/`stub`/`BYPASS`。
4. MySQL：按 [database/README.md](../database/README.md) 执行 V1 → V2 种子 → V3；创建最小权限账号。
5. 当前进程注入 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`，运行 `.\scripts\dev.ps1 -WithMySql`；只有健康 200 `UP` 才可称 MySQL 模式正常。
6. 放置合法数据文件，管理员在“数据质量”导入，在“模型评估”训练；核对批次计数、拒绝原因、时间切分与指标。
7. 生产前另行完成 TLS、反向代理、认证、限流、备份、监控、依赖审计与许可复核。

## 6. 恢复步骤

### 6.1 stub 模式

- 进程重启后内存状态丢失是预期行为。
- 重新导入同一文件即可重建批次（哈希幂等只在同一进程/数据库内有效）。
- 重新训练同一版本会覆盖 `runtime/models/<version>.json` 并新增评测记录；stub 重启后模型注册列表为空，但产物文件仍在。
- 若页面显示无模型但产物存在，重新训练或切换到 MySQL 持久化；不要手工伪造注册记录。

### 6.2 MySQL 模式

- 备份（示例，需按本机路径/账号调整；未在本项目验证）：
  `mysqldump --single-transaction --routines --triggers fraud_demo > fraud_demo_backup.sql`
- 恢复到空白库后再启动应用；恢复前停止写入，避免批次/审计半写。
- 迁移失败时不要重复执行非幂等种子；先检查 `V1/V3` 是否已执行、`DROP CHECK` 是否因版本过低失败。
- 模型产物丢失时，`model_registry` 仍有记录但 Java 训练校验会拒绝新注册；从备份恢复 `runtime/models` 或重新训练生成同版本产物。
- 应用账号只授予 DML；DDL 用管理账号，应用不使用 root。

### 6.3 端口/进程

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8000,8080,5173 -ErrorAction SilentlyContinue |
  Select-Object LocalPort,OwningProcess
```

只终止确认属于本项目的 PID；`dev.ps1` 退出时会定向清理自己启动的进程树。Maven 测试前必须停止开发后端，避免共享 `backend/target/classes`。

## 7. 残余风险

- MySQL V1/V3 与真实 200 UP 未验证；JSON/UTC/约束/事务行为仅有静态审查。
- 并发同文件导入存在“查哈希→插入”竞态，真实 MySQL 唯一键可能返回冲突而非幂等响应。
- Olist 原始时间按无时区解析记 UTC，未做巴西时区换算；仅演示，不应据此做真实时序结论。
- Olist 缺账户年龄/24h 频次，当前不生成 DWS 特征，故不自动可评分；没有用 0 或均值伪造。
- 训练为纯标准库单机实现；真实 IEEE 590k 行会受内存/耗时限制，训练段 >20k 确定性抽样。按 `card1+addr1` 清除跨段实体，可能丢弃较多验证/测试行；某段无正样本时训练失败并提示，不静默回填。
- 合成夹具 PR-AUC 可达 1.0，只证明管线可运行，不是模型效果。
- 演示认证/CSRF 不是生产安全；无 TLS、MFA、限流、会话吊销、审计防篡改、密钥管理。
- 浏览器断言覆盖 DOM/布局，不是完整 WCAG 或视觉审美审计。
- 公网/付费/域名暴露未获批准；默认只监听 loopback。
