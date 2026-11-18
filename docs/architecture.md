# 系统架构与技术契约基线

状态：v2 增量实现。v1 的固定规则评分、查询与复核契约保持；v2 增加演示会话认证、导入/质量/审计、IEEE-CIS 独立训练评测和五个业务页面。仍不含生产 IAM、分布式数仓、实时流、自动处置或论文。

## 1. 目标与边界

在单机 MySQL 中演示 ODS、DWD、DWS、ADS 逻辑分层，以及“导入 → 订单查询 → 风险信号/告警 → 人工复核”的接口；同时把 IEEE-CIS 交易欺诈评测放在独立数据域，避免把在线交易标签冒充订单真值。

不在范围：Kafka、Hadoop、Kubernetes、实时流、分布式调度、完整订单/支付业务、生产认证授权、自动封禁、生产级模型平台。不得把规则分称为真实欺诈概率，不得把告警或复核结论称为客观欺诈事实。

## 2. 技术选型理由

| 技术 | 职责 | 选择理由 | 限制 |
|---|---|---|---|
| Vue 3 + Vite + JavaScript | 登录、五页业务界面、健康 | 组件化、无额外 UI 框架、哈希路由免路由依赖 | 浏览器不是安全边界；不存密钥 |
| Spring Boot 2.7.18 | 对外 REST、校验、事务、权限、审计、导入协调、模型代理 | 分层清晰，统一错误与事务边界；JDK 8 本机兼容 | 公开维护已结束；长期迁移 JDK 17 + 受支持 Boot 3 |
| Python FastAPI | v1 规则评分/健康；v2 IEEE 训练、评测、产物 | Schema 明确；纯标准库训练降低演示依赖 | 不访问 MySQL、不处理复核；单机小规模 |
| MySQL 8.0 | 分层表、约束、事务、导入/审计/模型元数据 | 本科规模足够，事务直观 | 无高可用/水平扩展承诺；V3 需 8.0.16+ |
| Maven/npm/venv | 构建与依赖 | 常规工具、版本固定 | 首次需联网 |

**版本决策：** 本机只有 JDK 8，因此暂用 Spring Boot 2.7.18。这是环境兼容方案，不是长期安全选型；不虚称已完成 JDK 17 升级。

## 3. 模块边界与调用关系

```text
Browser (Vue 3)
    │  HTTP JSON + Session Cookie + CSRF
    ▼
Spring Boot :8080
    ├── JdbcTemplate → MySQL :3306（订单/特征/评分/告警/复核/导入/审计/模型）
    └── HTTP JSON → FastAPI :8000（/health、/internal/v1/score、/internal/v2/train）

Vue 不直连 Python/MySQL；Python 不访问 MySQL、不处理复核事务。
```

| 模块 | 可以做 | 不可以做 |
|---|---|---|
| Vue | 展示、筛选、提交复核/导入/训练、显示 401/403/409 | 计算最终风险、直连数据库、绕过 Java、存密钥 |
| Java | 认证/RBAC、CSRF、校验、事务、审计、导入幂等、模型响应校验 | 训练模型、把掩码当加密、记录明文敏感信息 |
| Python | 规则评分、IEEE 训练/时间切分评测、产物与健康 | 修改订单/复核、跨源合并标签、访问 MySQL |
| MySQL | 约束、索引、事务、血缘、审计、模型/评测元数据 | 存模型代码、替代应用层授权、假设跨源可关联 |

## 4. v2 数据流

1. **Olist 导入**：管理员触发；Java 读取 `data/external/olist`，按来源+文件哈希幂等登记批次；订单写 ODS/DWD（BRL、`OLIST-` 前缀、来源状态保留），缺失账户年龄/24h 频次保持 NULL，不造值。
2. **IEEE-CIS 导入**：只写 `eval_ieee_transaction`，保留 `is_fraud` 与交易时特征；绝不写订单表、绝不与订单 JOIN。
3. **DWS**：只对具备完整特征的订单生成评分快照；Olist 因缺账户年龄/频次，不伪造特征、不自动可评分。
4. **规则评分**：Java 读 DWS 快照 → Python `/internal/v1/score` → 校验版本/类型/阈值/规则码 → 写 `ads_risk_score`；告警/复核保持分离。
5. **人工复核**：身份取会话 `Authentication`（请求体 `reviewerId` 被忽略），`INCONCLUSIVE→REVIEWING`，`CONFIRMED/REJECTED→RESOLVED`，乐观锁 `version`；写 `ads_alert_review` 与 `audit_event`。
6. **训练/评测**：管理员提交版本 → Java 校验 IEEE 文件存在 → Python 按 `TransactionDT` 70/15/15 时间切分、以 `card1+addr1` 清除跨段实体（记录丢弃行数）、训练段拟合、验证段选阈值、测试段评估 → 产物 `runtime/models/*.json` → Java 校验产物存在后写 `model_registry`/`model_evaluation` 并审计。
7. **数据质量**：每批写行数、支付覆盖、金额、日期、标签/时间可切分等检查；质量通过不等于模型有效。

## 5. 认证、健康与错误边界

- 演示账号：`demo_analyst`/`demo_reviewer`/`demo_admin`；BCrypt + 内存 `UserDetailsService`，密码可由环境变量覆盖。不是生产 IAM。
- 会话：`JSESSIONID`，`HttpOnly`、`SameSite=Lax`、本地 `Secure=false`；登录 `changeSessionId()`。
- CSRF：`CookieCsrfTokenRepository`，`X-XSRF-TOKEN`；登录豁免，其余写操作校验；失败 `403 CSRF_INVALID`。
- 授权：导入/训练/审计/运行日志查询/日志健康仅 ADMIN；复核 REVIEWER/ADMIN；客户端错误上报仅登录；其余业务端点登录即可；健康匿名。
- `mysql`：需 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`；`stub`：数据库 `BYPASS`、聚合 `DEGRADED`，不证明 MySQL 可用。
- `/api/v1/health`：UP 200，DEGRADED 503；stub 503 是预期。健康不暴露主机、账号、连接串、堆栈。
- 评分：连接 2s、读取 3s；不可用 502 `MODEL_UNAVAILABLE`，响应违约 502 `MODEL_INVALID_RESPONSE`；参数 400，资源 404，复核冲突 409，媒体类型 415。
- 导入/训练：缺文件 400 `IMPORT_SOURCE_NOT_FOUND`；模型产物必须存在且位于 `runtime/models`，否则 502。
- 登录失败统一 401，不区分用户/密码；审计不记录密码、Cookie、请求正文。

## 6. M3 运行诊断日志

- M3 新增结构化 `ops_log_event`，与 `audit_event` 分离：审计是不可变操作历史，运行日志是可清理诊断事件。
- 来源为 `FRONTEND/BACKEND/MODEL/TASK`；Java 只在统一异常、导入、训练、配置发布、工单和模型调用边界记录固定 code。
- 客户端只上报 API 失败，服务端把固定 code 映射 level/摘要；route 使用固定模板 key，不接收原始 URL/查询/自由文本。
- 成功事件在业务事务提交或业务调用返回后记录；失败事件不产生成功记录。导入/训练没有单一事务，只表示服务调用完成。
- 日志写入使用独立事务并吞掉运行时异常，故障通过 ADMIN-only 进程级 health 显示 `DEGRADED`；不影响业务，不吞 JVM Error。
- 保留策略为 30 天/50,000 条软上限，由专用保留任务分批清理；不是通用调度平台。
- 前端仅 ADMIN 右下角浮窗，打开且未暂停时 5 秒轮询；无后台未读轮询、无服务器删除。

## 7. M4 效能总览

- 新增 ADMIN-only `GET /api/v3/dashboard/overview`；只返回聚合计数，不返回订单/客户/复核正文。
- 时间固定 UTC，`[from,to)`，最大 366 天；默认最近 366 天、ISO 周；日粒度最多 90 天；首尾 partial。
- 订单趋势按实际 `dwd_order.data_origin` 分开（OLIST/SYNTHETIC），只计数量，不汇总跨币种金额；IEEE-CIS 评测不进入接口。
- 趋势按创建时间补零；当前告警/工单状态是独立 `asOf` 快照，不冒充历史状态。
- 导入只取范围内最近 20 个终态批次（SUCCEEDED/PARTIAL/FAILED），排除 RUNNING；DQ 通过率只使用 ERROR/WARN，INFO 单独计数。
- 前端使用原生 SVG/CSS 折线、环图、柱状图，不引入 ECharts；不实现雷达图。
- 未新增 V6 索引；大数据量查询性能仍依赖后续独立审批。

## 8. 数据隐私与安全

见 [privacy-and-security.md](privacy-and-security.md)。核心：仅虚构/匿名演示；最少字段；掩码≠加密；确需敏感字段才引入应用层 AES-GCM 与仓库外密钥；日志不记录明文敏感值、密码、Cookie、原始载荷；外部数据按许可隔离，不公开再分发 IEEE-CIS 行级数据。

## 9. 交付验收边界

已实现：五页、会话认证/RBAC/CSRF、导入幂等与质量、审计、模型注册/时间切分评测、v1 规则评分、stub/MySQL 双实现、M2 工单、M3 结构化运行日志、M4 效能总览、自动化测试与浏览器断言。

未验收：MySQL V1–V5 实际执行、200 UP、事务/约束/JSON/UTC/日志游标与保留清理真实行为；外部真实 Olist/IEEE 数据导入；真实模型效果；生产部署、TLS、限流（除客户端上报单实例限流外）、备份恢复、密钥管理、依赖漏洞全扫。以 [verification.md](verification.md) 为准。
