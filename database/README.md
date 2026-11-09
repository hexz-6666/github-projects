# MySQL 初始化与核验

只面向 MySQL 8.0（V3 的 `DROP CHECK` 需 8.0.16+）和独立空白演示库 `fraud_demo`。ODS/DWD/DWS/ADS 是单库表名前缀，不是分布式物理数仓。所有种子均为独立虚构数据，不得换成真实个人信息。

## 初始化顺序

1. 在 MySQL 客户端中使用**你自己的管理账号**连接；不要在仓库、命令行历史或聊天中写密码。
2. 执行 `migrations/V1__init.sql`：建库、6 张 v1 表、约束与索引。
3. 在空白库执行一次 `seed/V2__synthetic_demo.sql`。它是种子脚本，不是迁移脚本，**不幂等**，重复执行会发生主键/唯一键冲突。
4. 执行 `migrations/V3__v2_sources_eval.sql`：放开 v1 的单源/单币种限制，新增 `etl_import_batch`、`dq_check_result`、`audit_event`、`model_registry`、`model_evaluation`、`eval_ieee_transaction`。
5. 执行 `verify_synthetic.sql`：预期 3 个 ODS、3 个 DWD、3 个 DWS、1 个评分、1 个告警、0 个复核（v2 新表初始为空）。
6. 另建应用账号，只授予 `fraud_demo` 的 SELECT/INSERT/UPDATE/DELETE；DDL 由管理账号执行，应用不使用 root。

```powershell
mysql -u root -p --execute="source E:/beysesji/database/migrations/V1__init.sql"
mysql -u root -p --execute="source E:/beysesji/database/seed/V2__synthetic_demo.sql"
mysql -u root -p --execute="source E:/beysesji/database/migrations/V3__v2_sources_eval.sql"
mysql -u root -p --execute="source E:/beysesji/database/verify_synthetic.sql"
```

PowerShell 不支持 `< file` 输入重定向，以上使用 `mysql.exe` 的 `source`。项目不在 `E:/beysesji` 时替换绝对路径。用户名只是示例，不要把密码复制到命令行、历史或 `.env.example`。

## v2 数据边界

- Olist 订单写入 `ods_order_raw`/`dwd_order`，`data_origin=OLIST`、币种 BRL；缺失的账户年龄/24h 频次保持 NULL，不造值。
- IEEE-CIS 只写入 `eval_ieee_transaction`，保留 `is_fraud` 但**只属于该数据集**；不得与 `dwd_order` JOIN，不得把标签写入订单或告警。
- 订单表仍没有 `is_fraud` 字段；人工复核结论不自动成为训练标签。
- 导入幂等键唯一；并发同文件导入的竞态尚未在真实 MySQL 验证。
- 模型产物只存相对路径 `runtime/models/<version>.json`，文件在 Git 忽略的 `runtime/` 下；数据库不存模型代码。

## 未验证范围

本机 MySQL80 服务和 3306 端口已发现，但没有可用的独立演示账号，未猜测密码。**未实际执行** V1/V2/V3、约束、索引、JSON、UTC、导入、事务、评分写入或健康 `UP`；不据此宣称 MySQL 模式通过。获得凭据后按根目录 README §5 使用 `scripts/dev.ps1 -WithMySql` 验证，并更新 [verification.md](../docs/verification.md)。
