# MySQL 初始化与核验

只面向 MySQL 8.0（V3 的 `DROP CHECK` 需 8.0.16+）和独立空白演示库 `fraud_demo`。ODS/DWD/DWS/ADS 是单库表名前缀，不是分布式物理数仓。所有种子均为独立虚构数据，不得换成真实个人信息。

## 初始化顺序

1. 在 MySQL 客户端中使用**你自己的管理账号**连接；不要在仓库、命令行历史或聊天中写密码。
2. 执行 `migrations/V1__init.sql`：建库、6 张 v1 表、约束与索引。
3. 在空白库执行一次 `seed/V2__synthetic_demo.sql`。它是种子脚本，不是迁移脚本，**不幂等**，重复执行会发生主键/唯一键冲突。
4. 执行 `migrations/V3__v2_sources_eval.sql`：放开 v1 的单源/单币种限制，新增 `etl_import_batch`、`dq_check_result`、`audit_event`、`model_registry`、`model_evaluation`、`eval_ieee_transaction`。
5. 执行 `migrations/V4__v3_work_orders.sql`：新增工单配置、不可变配置版本、工单、工单事件表，以及复核/配置版本组合约束。V4 必须在 V1–V3 之后。
6. 执行 `verify_synthetic.sql`：预期 3 个 ODS、3 个 DWD、3 个 DWS、1 个评分、1 个告警、0 个复核（v2 新表初始为空）。
7. 另建应用账号，只授予 `fraud_demo` 的 SELECT/INSERT/UPDATE/DELETE；DDL 由管理账号执行，应用不使用 root。

### V4 可重复执行与部分执行

- V4 用 `CREATE TABLE IF NOT EXISTS` 建表，并在文件末尾用 `information_schema` 守卫逐条添加唯一键/外键；不包含无条件 `ADD CONSTRAINT`，不删表、不删数据。
- **V4 对预期结构的干净库、已执行库和缺少对象的部分执行库可重复执行；同名但定义错误的对象不会被自动修复。** 守卫只检查对象名，不验证既有对象的列顺序、引用表/列或 CHECK 定义；因此“可重复执行”不等于“任意 schema 漂移可修复”。
- **干净库**：执行一次建表并补齐约束；再次执行时守卫跳过已存在项，应成功。
- **已执行 V4 的库**：再次执行应只做守卫检查；不要另写 `ALTER TABLE ... ADD CONSTRAINT` 重复添加。
- **部分执行**：若建表成功但约束未补齐，再次执行 V4 会补缺失的 `uk_review_id_alert`、`uk_wocv_id_config_version`、`fk_woc_published_version`、`fk_wo_config_version_consistent`、`fk_wo_resolution_review_alert`。若同名对象存在但定义错误，守卫不会发现或修复，必须先人工核验并另行变更。
- V4 使用 MySQL 客户端 `source` 执行；`PREPARE`/`EXECUTE` 语句要求 MySQL 8.0.16+。项目没有 Flyway/Liquibase schema history，因此不把“文件可重复执行”等同于“迁移工具已接管”。
- MySQL 门控测试在**同时**设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 时才运行；缺任一项整类跳过。可在 `backend` 下运行 `mvn -q "-Dtest=M2MysqlIT" test`，也可在仓库根目录运行 `mvn -f backend/pom.xml -q "-Dtest=M2MysqlIT" test`；V4 路径按 Maven `basedir`/`user.dir` 及其父目录解析，不依赖固定 `../database` 启动目录。测试会先用 V4 守卫建/补表，独立创建 `IT-` 前缀的订单/特征/评分/告警/配置 fixture，检查组合索引/外键/CHECK 的精确列与引用，再尝试违规版本三元组、非法状态、跨告警复核、唯一键冲突翻译、两个建单线程、建单/复核并发，以及超长 `request_id` 使 event 写入失败后的回滚断言。清理只针对本次 fixture 行：审计按本次唯一 `request_id` + `target_type='WORK_ORDER'` + `action='WORK_ORDER_CREATED'` 删除，不按裸 `target_id`，不删除 V2 种子或既有业务历史。该测试 2026-11-12 的门控运行被跳过（三个变量均未设置），没有连接数据库，8 个测试方法均未实际执行。
- 如果**历史数据已违规**，组合外键会拒绝添加。先用只读查询定位，不要自动删数据或清空表：
  - 复核跨告警：`work_order.resolution_review_id` 对应的 `ads_alert_review.alert_id` 与 `work_order.alert_id` 不一致；
  - 版本不一致：`work_order.config_version_id` 对应的版本行的 `(config_id, version_no)` 与工单上的 `(config_id, config_version_no)` 不一致。
  修复方式只能是人工核对后修正该工单的引用/业务数据，或保留违规记录并暂缓添加约束；V4 不会擅自清理历史。
  ```sql
  SELECT wo.id, wo.alert_id, r.review_id, r.alert_id AS review_alert_id
  FROM work_order wo JOIN ads_alert_review r ON r.review_id = wo.resolution_review_id
  WHERE r.alert_id <> wo.alert_id;

  SELECT wo.id, wo.config_id, wo.config_version_no,
         v.id AS version_id, v.config_id AS version_config_id, v.version_no
  FROM work_order wo JOIN work_order_config_version v ON v.id = wo.config_version_id
  WHERE v.config_id <> wo.config_id OR v.version_no <> wo.config_version_no;
  ```

```powershell
mysql -u root -p --execute="source E:/beysesji/database/migrations/V1__init.sql"
mysql -u root -p --execute="source E:/beysesji/database/seed/V2__synthetic_demo.sql"
mysql -u root -p --execute="source E:/beysesji/database/migrations/V3__v2_sources_eval.sql"
mysql -u root -p --execute="source E:/beysesji/database/migrations/V4__v3_work_orders.sql"
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

本机 MySQL80 服务和 3306 端口已发现，但没有可用的独立演示账号，未猜测密码。**未实际执行** V1–V4、约束、索引、JSON、UTC、导入、事务失败回滚、评分写入或健康 `UP`；`M2MysqlIT` 的 8 个测试方法均未实际运行，不据此宣称 MySQL 模式通过。获得凭据后按根目录 README §5 使用 `scripts/dev.ps1 -WithMySql` 验证，并更新 [verification.md](../docs/verification.md)。
