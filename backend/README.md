# Fraud API（Spring Boot 2.7.18 / Java 8）

对外 REST、会话认证/RBAC/CSRF、订单/告警/复核、导入协调、数据质量、审计、模型注册/评测与健康。业务数据只经 Java 访问 MySQL；Python 只做评分/训练。

## 运行模式

### stub（默认，无需数据库密码）

```powershell
mvn spring-boot:run
```

`StubFraudStore` + `StubV2Store` 显式内存实现：3 个虚构订单/特征/评分/告警；导入、模型、审计与复核修改重启后丢失。健康固定 503 `DEGRADED`，`storageMode=stub`，数据库 `BYPASS`，不冒充 MySQL。

### MySQL

先按 [database/README.md](../database/README.md) 执行 V1 → 种子 V2 → V3，再：

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/fraud_demo?useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
$env:DB_USERNAME = "your-demo-user"
$securePassword = Read-Host 'Local demo database password' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
try {
  mvn spring-boot:run "-Dspring-boot.run.profiles=mysql"
} finally {
  Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}
```

只有 `mysql` profile 创建 Hikari/JdbcTemplate/事务管理器。真实 MySQL 尚未在本机验证。

## 认证与权限

- 演示账号：`demo_analyst`、`demo_reviewer`、`demo_admin`；BCrypt + 内存 `UserDetailsService`，密码可由 `APP_DEMO_*_PASSWORD` 覆盖。
- Session：`JSESSIONID`，`HttpOnly`、`SameSite=Lax`、本地 `Secure=false`；登录 `changeSessionId()`。
- CSRF：`X-XSRF-TOKEN`，登录豁免；其余写操作缺失令牌 403。
- 权限：导入/训练/审计 ADMIN；复核 REVIEWER/ADMIN；其余业务端点登录；健康匿名。
- 复核身份取会话 `Authentication`，请求体 `reviewerId` 仅兼容旧客户端，不参与身份写入。

## 模型地址与超时

`MODEL_BASE_URL` 默认 `http://127.0.0.1:8000`，只允许回环 HTTP(S) origin（无路径/查询/凭据）。评分连接 2s、读取 3s；训练连接 5s、读取 5min。模型不可用/响应违约返回 502，不保存评分或模型注册。

## 导入与训练

- `app.import-root` 默认 `data/external`；从 `backend/` 启动时自动回退到项目根同名目录。
- Olist 需要 orders + payments；customers 可选；IEEE 需要 `train_transaction.csv`。
- 文件 SHA-256 幂等；缺文件 400 `IMPORT_SOURCE_NOT_FOUND`。
- 训练只接受 `IEEE_CIS`；校验版本、类型、来源与产物存在；非法响应 502。

## 构建与测试

```powershell
mvn clean test
mvn package
```

当前 **33 项**测试：`ApiContractTest` 14、`ModelClientTest` 11、`V2ApiTest` 8。覆盖 Session/CSRF 401/403、复核身份、导入幂等、质量、审计、模型注册/评测、非法模型响应、v1 查询/复核/评分。MySQL 集成测试需真实库，当前不声称通过。

服务仅监听 `127.0.0.1:8080`；金额为字符串，时间 UTC RFC 3339；Jackson 拒绝未知字段；错误不返回输入值、SQL、堆栈、密钥或下游正文。

## 非生产限制

仅本机演示。认证/CSRF 是演示实现，不是生产 IAM；无 MFA、TLS、限流、会话吊销、KMS、审计防篡改。不可接真实个人/支付数据，不可把规则分/模型分/告警/复核结论当成欺诈事实或无偏训练标签。
