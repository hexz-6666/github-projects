# 数仓分层与数据字典

## 1. 分层原则

MySQL 8.0 单库 `fraud_demo`，以 `ods_`、`dwd_`、`dws_`、`ads_` 表名前缀区分逻辑层；金额 `DECIMAL(18,2)`，最多 16 位整数和 2 位小数；时间 `DATETIME(6)` UTC，字符集 `utf8mb4`。浏览器订单 JSON 金额使用字符串避免 JavaScript 精度歧义；Java 与 Python 的评分内部契约使用 JSON 十进制数。

| 层 | 表 | 粒度 | 主要任务 | 不允许 |
|---|---|---|---|---|
| ODS | `ods_order_raw` | 每来源每原始订单一行 | 保留原始状态、来源和批次血缘 | 原始字段直接充当欺诈标签 |
| DWD | `dwd_order` | 每订单一行 | 标准化订单、支付及客户演示字段 | 跨来源伪造关联；写 `is_fraud` |
| DWS | `dws_order_risk_feature` | 每订单每特征版本一行 | 保存可复现评分特征 | 使用评分之后的数据造成泄漏；为缺失源造值 |
| ADS | `ads_risk_score` | 每次成功评分一行 | 保存模型输出与规则解释 | 分数写成真实欺诈概率 |
| ADS | `ads_risk_alert` | 每评分批次每订单至多一行 | 处置队列及状态 | 告警直接等同欺诈事实 |
| ADS | `ads_alert_review` | 每次复核一行 | 复核结论与审计 | 覆盖历史、由客户端伪造 reviewer |
| v2 | `etl_import_batch` | 每次导入一行 | 幂等键、批次状态、计数、操作者 | 存原始敏感载荷 |
| v2 | `dq_check_result` | 每批每检查一行 | 数据质量证据 | 把质量通过当模型有效 |
| v2 | `audit_event` | 每次操作一行 | 追加式操作审计 | 存请求正文/密码/Cookie |
| v2 | `model_registry` | 每模型版本一行 | 类型、来源、产物路径、特征 Schema | 存模型代码/真实敏感数据 |
| v2 | `model_evaluation` | 每次评测一行 | 时间切分与不平衡指标 | 声称生产有效性 |
| v2 | `eval_ieee_transaction` | 每 IEEE 交易一行 | 独立欺诈标签评测域 | 与 `dwd_order` JOIN、写回订单 |

字段长度、约束、默认值和索引以 [V1__init.sql](../database/migrations/V1__init.sql) 与 [V3__v2_sources_eval.sql](../database/migrations/V3__v2_sources_eval.sql) 为可执行基准。

## 2. ODS：`ods_order_raw`

| 字段 | 类型 | 含义与约束 |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | 技术主键 |
| `source_system` | VARCHAR(32) | `SYNTHETIC`/`OLIST`；不同来源不互作外键 |
| `source_record_id` | VARCHAR(64) | 来源内部记录 ID，仅同来源内唯一 |
| `order_no` | VARCHAR(64) | 原始/标准订单号 |
| `customer_ref` | VARCHAR(64) | 虚构/匿名客户引用 |
| `customer_email` | VARCHAR(254) NULL | 演示邮箱；外部源不写入真实邮箱 |
| `order_status` | VARCHAR(32) | 原始业务状态，不是欺诈标签 |
| `amount`、`currency` | DECIMAL(18,2)、CHAR(3) | 原始金额与币种；Olist 为 BRL |
| `payment_count` | INT | 支付记录/尝试数 |
| `account_created_at` | DATETIME(6) NULL | Olist 缺失时为 NULL，不造值 |
| `ordered_at` | DATETIME(6) | 下单时间；Olist 原始时间按无时区解析后记 UTC，未做巴西时区换算 |
| `transaction_count_24h` | INT NULL | Olist 缺失时为 NULL |
| `ingest_batch_id` | VARCHAR(64) | 批次血缘 |
| `payload_hash` | CHAR(64) | 规范化载荷 SHA-256 |
| `raw_payload` | JSON | 原始载荷；禁止真实敏感信息 |
| `dataset_name` | VARCHAR(64) | 数据集/批次名称，默认 `synthetic-demo-v1` |
| `license_ref` | VARCHAR(128) NULL | 许可标识/官方链接，仅元数据 |
| `source_status` | VARCHAR(32) NULL | 来源原始状态，不做标签映射 |
| `ingested_at` | DATETIME(6) | 入库 UTC |

唯一键：`(source_system, source_record_id)`。V3 已移除 v1 的“仅 SYNTHETIC”约束，但应用层仍只接受已知来源。

## 3. DWD：`dwd_order`

| 字段 | 类型 | 含义与约束 |
|---|---|---|
| `order_id` | VARCHAR(64) PK | 本系统订单 ID；Olist 加 `OLIST-` 前缀 |
| `source_system`、`source_record_id`、`ingest_batch_id` | VARCHAR | 来源与批次 |
| `order_no` | VARCHAR(64) | 标准订单号，唯一 |
| `customer_ref` | VARCHAR(64) | 匿名客户主体引用 |
| `customer_email_masked` | VARCHAR(254) NULL | 展示掩码；不是加密 |
| `status` | VARCHAR(32) | `CREATED`/`PAID`/`CANCELLED`；业务状态非欺诈标签 |
| `amount`、`currency` | DECIMAL(18,2)、CHAR(3) | 标准金额；OLIST 为 BRL |
| `payment_count` | INT | 支付记录数，≥1 |
| `account_created_at` | DATETIME(6) NULL | 外部源缺失时为 NULL |
| `ordered_at` | DATETIME(6) | 下单时间 |
| `transaction_count_24h` | INT NULL | 外部源缺失时为 NULL |
| `data_origin` | VARCHAR(16) | `SYNTHETIC`/`OLIST` |
| `dataset_name` | VARCHAR(64) | 数据集名 |
| `source_status` | VARCHAR(32) NULL | 来源原始状态 |
| `created_at`、`updated_at` | DATETIME(6) | 技术时间 |

Olist `order_status` 映射：`canceled`/`unavailable` → `CANCELLED`，`created` → `CREATED`，其余 → `PAID`。这是履约状态映射，**不是**欺诈标签。DWD 不存明文真实邮箱，也不存 `is_fraud`。

## 4. DWS：`dws_order_risk_feature`

| 字段 | 类型 | 含义与约束 |
|---|---|---|
| `feature_id` | BIGINT PK | 特征快照 ID |
| `order_id` | VARCHAR(64) FK | 对应 DWD 订单 |
| `feature_version` | VARCHAR(32) | 如 `synthetic-feature-v1` |
| `amount`、`currency` | DECIMAL(18,2)、CHAR(3) | 评分时金额/币种 |
| `payment_count` | INT | 支付尝试数 |
| `account_age_days` | INT | `floor(ordered_at-account_created_at)/天`，≥0 |
| `transaction_count_24h` | INT | 近 24 小时次数 |
| `as_of_at` | DATETIME(6) | 特征截止时间；只用当时及之前数据 |
| `source_batch_id` | VARCHAR(64) | 输入批次血缘 |
| `created_at` | DATETIME(6) | 快照创建时间 |

唯一键：`(order_id, feature_version)`。V3 将 `account_age_days`、`transaction_count_24h` 改为可空，是为了外部源缺失时不造值；Olist 当前不生成 DWS 快照，因此不自动可评分。

## 5. ADS：评分、告警、复核

### `ads_risk_score`

`score_id` BIGINT PK；`order_id` FK；`feature_id` FK；`score` DECIMAL(5,4) ∈[0,1]；`risk_level` LOW/MEDIUM/HIGH；`model_version`、`model_type`、`feature_version`；`model_id` BIGINT NULL（v2，DEMO_RULE 为 NULL）；`threshold` DECIMAL(5,4)；`rule_codes` JSON 数组；`scored_at`、`created_at`。索引 `(order_id, scored_at)`。

- v1 规则：`model_type=DEMO_RULE`、`model_version=synthetic-rule-v1`。
- v2 训练模型：`model_type=TRAINED_*`，`model_id` 指向 `model_registry`。
- 两者分数都不是真实欺诈概率；不得把 `TRAINED_LOGREG` 改标为 `DEMO_RULE`，反之亦然。

### `ads_risk_alert`

`alert_id` PK；`order_id` FK；`score_id` FK 且唯一；`alert_status` OPEN/REVIEWING/RESOLVED；`risk_level`；`reason_codes` JSON；`version` INT 乐观锁从 1 开始；`created_at`、`updated_at`。告警只是待检查信号。

### `ads_alert_review`

`review_id` PK；`alert_id` FK；`reviewer_id` VARCHAR(64) 取**服务端认证用户名**；`review_result` CONFIRMED/REJECTED/INCONCLUSIVE；`comment` ≤500 字，只许虚构非敏感备注；`previous_status`、`new_status`；`created_at`。追加历史；告警 `version` 条件更新防覆盖。复核结论不自动成为训练标签或客观真值。

## 6. v2 表

### `etl_import_batch`

`batch_id` PK；`source_system`；`dataset_name`；`idempotency_key` UNIQUE（来源+必需文件 SHA-256）；`status` RUNNING/SUCCEEDED/FAILED/PARTIAL；`total_rows`/`accepted_rows`/`rejected_rows`；`file_count`；`operator_id`；`error_summary`；`started_at`/`finished_at`。不存原始载荷。

### `dq_check_result`

`check_id` PK；`batch_id` FK；`check_code`；`severity` ERROR/WARN/INFO；`passed`；`observed`/`expected`/`detail`；`created_at`。只记录导入质量，不是模型效果。

### `audit_event`

`event_id` PK；`actor_id`；`actor_role`；`action`（LOGIN/SCORE_RUN/REVIEW_SUBMIT/IMPORT_RUN/MODEL_TRAIN）；`target_type`/`target_id`；`result` SUCCESS/DENIED/FAILED；`request_id`；`detail_json`（仅非敏感计数/类型）；`created_at`。追加式，不更新/删除历史。

### `model_registry`

`model_id` PK；`model_version` UNIQUE；`model_type` `TRAINED_*`；`source_system`（当前 `IEEE_CIS`）；`artifact_path`（`runtime/models/<version>.json`，不提交）；`feature_schema` JSON；`trained_at`；`training_rows`；`positive_rate`；`status`；`created_at`。

### `model_evaluation`

`eval_id` PK；`model_version`；`source_system`；`split_strategy=TIME_70_15_15`；`entity_grouping=CARD_ADDR_PURGED`；`valid_rows_dropped`/`test_rows_dropped`；`train_rows`/`valid_rows`/`test_rows`/`test_positives`；`base_rate`；`pr_auc`/`roc_auc`；`recall_at_fpr_1`/`recall_at_fpr_5`；`precision_at_100`；`brier`；`threshold`；`confusion_json`；`created_at`。指标是离线演示结果，不是生产有效性证明。

### `eval_ieee_transaction`

`transaction_id` PK；`source_system=IEEE_CIS`；`dataset_name`；`transaction_dt` BIGINT（相对时间，不伪造日期）；`transaction_amt`；`is_fraud` TINYINT（**仅该数据集标签**）；`feature_json`（匿名/数值交易时特征）；`import_batch_id`；`imported_at`。禁止与 `dwd_order` JOIN，禁止把标签写回订单/告警。

## 7. 标签与评测语义

1. Olist **没有**直接真实欺诈标签；取消、退款、差评、延迟都不是欺诈真值。
2. IEEE-CIS 的 `isFraud` 只在其在线交易域有效，不能外推到 Olist 订单，也不能与订单表拼接补标。
3. 规则分/模型分都是风险排序分，不是校准后的真实欺诈概率；告警是待检查信号；人工复核是判断记录，不是无偏真值。
4. 训练只使用 IEEE-CIS；按 `TransactionDT` 70/15/15 时间切分，禁止随机切分；以 `card1+addr1` 清除跨段实体（验证/测试不再含训练实体），记录清除行数；实体键缺失时退化为唯一交易 ID。
5. 特征只用交易时可见字段；排除 `isFraud`/`TransactionID`；不做目标编码；不用测试段调参；实体清除后某段为空/无正样本则训练失败，不静默回填。
6. 不平衡指标必须报告 PR-AUC、ROC-AUC、Recall@FPR 1%/5%、Precision@100、Brier、混淆矩阵、基率，不能只报准确率。
7. 纯标准库演示实现；训练段 >20,000 行时确定性抽样，验证/测试段完整；内存与单机性能有限。
8. `precisionAt100` 在测试集不足 100 行时按实际行数计算；报告时需同时看 `testRows`。

## 8. 演示固定规则（v1，保持不变）

基础分 0.05；金额 ≥1000 加 0.45；支付尝试 ≥3 加 0.25；账户年龄 ≤7 天加 0.20；24 小时交易 ≥5 加 0.10；总分上限 0.99。LOW `<0.40`，MEDIUM `[0.40,0.70)`，HIGH `≥0.70`。原因码依次 `HIGH_AMOUNT`、`REPEATED_PAYMENT_ATTEMPTS`、`NEW_ACCOUNT`、`HIGH_VELOCITY`。

这是契约验证规则，未做统计训练、真实标签校准、精确率/召回率评估或有效性承诺。

## 9. M4 效能总览聚合

- `GET /api/v3/dashboard/overview` 只返回聚合计数，不返回订单、客户或复核正文。
- 订单趋势按 `dwd_order.ordered_at` 和实际 `data_origin` 分组；只计数量，不汇总跨币种金额。
- 告警、工单、复核分别按 `created_at` 归桶；`work_order.status` 与告警/工单状态快照使用 `asOf`，不冒充历史状态。
- 复核推进只统计 `ads_alert_review.new_status='RESOLVED'` 的次数，不称工单完成量。
- 导入只取范围内最近 20 个 `SUCCEEDED/PARTIAL/FAILED` 批次，按 `etl_import_batch.started_at` 倒序；排除 `RUNNING`。
- DQ 通过率只使用 `dq_check_result.severity IN ('ERROR','WARN')`；INFO 不进入分子/分母，单独计数；无 ERROR/WARN 检查项时为 null。
- IEEE-CIS `model_evaluation` 不进入该聚合；本版不实现雷达图。
