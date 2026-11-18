-- V3: v2 增量迁移。保留 V1 历史，不重写已执行脚本。
-- 目的：允许独立来源（SYNTHETIC/OLIST/IEEE_CIS）、导入批次、数据质量、
-- 审计、模型注册/评测，以及 IEEE-CIS 独立评测域。
-- 前置：已按顺序执行 V1、V2。MySQL 8.0.16+（DROP CHECK 语法）。

USE fraud_demo;

-- ---------- 1. 放开 v1 的单源/单币种/三状态限制 ----------
ALTER TABLE ods_order_raw DROP CHECK ck_ods_synthetic;
ALTER TABLE ods_order_raw
  MODIFY account_created_at DATETIME(6) NULL COMMENT 'UTC；Olist 无账户创建时间时为 NULL',
  MODIFY transaction_count_24h INT NULL COMMENT 'Olist 无实时窗口时为 NULL，不造值',
  ADD COLUMN dataset_name VARCHAR(64) NOT NULL DEFAULT 'synthetic-demo-v1' COMMENT '数据集/批次名称',
  ADD COLUMN license_ref VARCHAR(128) NULL COMMENT '许可标识或官方链接，仅元数据',
  ADD COLUMN source_status VARCHAR(32) NULL COMMENT '来源原始状态，不映射为欺诈标签';

ALTER TABLE dwd_order DROP CHECK ck_dwd_origin;
ALTER TABLE dwd_order DROP CHECK ck_dwd_status;
ALTER TABLE dwd_order DROP CHECK ck_dwd_currency;
ALTER TABLE dwd_order
  MODIFY account_created_at DATETIME(6) NULL COMMENT 'UTC；外部源缺失时为 NULL',
  MODIFY transaction_count_24h INT NULL COMMENT '外部源缺失时为 NULL，不造值',
  ADD COLUMN dataset_name VARCHAR(64) NOT NULL DEFAULT 'synthetic-demo-v1',
  ADD COLUMN source_status VARCHAR(32) NULL COMMENT '来源原始业务状态，不是欺诈标签';

ALTER TABLE dws_order_risk_feature DROP CHECK ck_dws_currency;

ALTER TABLE ads_risk_score DROP CHECK ck_score_model;
ALTER TABLE ads_risk_score
  ADD COLUMN model_id BIGINT NULL COMMENT 'v2：model_registry.model_id；DEMO_RULE 为 NULL';

-- 外部订单金额仍必须为正；Olist 零金额支付记录在导入时进入拒绝计数。
-- 状态不再由数据库枚举强制，应用层仍只接受已知映射，避免把状态当标签。

-- ---------- 2. 导入批次与幂等 ----------
CREATE TABLE IF NOT EXISTS etl_import_batch (
  batch_id VARCHAR(64) NOT NULL,
  source_system VARCHAR(32) NOT NULL,
  dataset_name VARCHAR(64) NOT NULL,
  idempotency_key CHAR(64) NOT NULL COMMENT '源+文件哈希，重复请求复用',
  status VARCHAR(16) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED/PARTIAL',
  total_rows INT NOT NULL DEFAULT 0,
  accepted_rows INT NOT NULL DEFAULT 0,
  rejected_rows INT NOT NULL DEFAULT 0,
  file_count INT NOT NULL DEFAULT 0,
  operator_id VARCHAR(64) NOT NULL,
  error_summary VARCHAR(500) NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (batch_id),
  UNIQUE KEY uk_import_idempotency (idempotency_key),
  KEY idx_import_source_time (source_system, started_at)
) ENGINE=InnoDB COMMENT='v2：导入批次与幂等；不存原始敏感载荷';

-- ---------- 3. 数据质量 ----------
CREATE TABLE IF NOT EXISTS dq_check_result (
  check_id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id VARCHAR(64) NOT NULL,
  check_code VARCHAR(64) NOT NULL,
  severity VARCHAR(8) NOT NULL COMMENT 'ERROR/WARN/INFO',
  passed TINYINT NOT NULL,
  observed VARCHAR(256) NULL,
  expected VARCHAR(256) NULL,
  detail VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (check_id),
  KEY idx_dq_batch (batch_id),
  CONSTRAINT fk_dq_batch FOREIGN KEY (batch_id) REFERENCES etl_import_batch(batch_id)
) ENGINE=InnoDB COMMENT='v2：导入数据质量检查结果';

-- ---------- 4. 审计 ----------
CREATE TABLE IF NOT EXISTS audit_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT,
  actor_id VARCHAR(64) NOT NULL,
  actor_role VARCHAR(64) NULL,
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NULL,
  target_id VARCHAR(64) NULL,
  result VARCHAR(16) NOT NULL COMMENT 'SUCCESS/DENIED/FAILED',
  request_id VARCHAR(64) NULL,
  detail_json JSON NULL COMMENT '仅非敏感元数据，禁止请求正文和凭据',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (event_id),
  KEY idx_audit_time (created_at),
  KEY idx_audit_actor (actor_id, created_at)
) ENGINE=InnoDB COMMENT='v2：操作审计，追加式';

-- ---------- 5. 模型注册与评测 ----------
CREATE TABLE IF NOT EXISTS model_registry (
  model_id BIGINT NOT NULL AUTO_INCREMENT,
  model_version VARCHAR(64) NOT NULL,
  model_type VARCHAR(32) NOT NULL COMMENT 'TRAINED_LOGREG 等，不复用 DEMO_RULE',
  source_system VARCHAR(32) NOT NULL COMMENT '训练来源，当前仅 IEEE_CIS',
  artifact_path VARCHAR(256) NOT NULL COMMENT '仓库外/runtime 相对路径，不提交',
  feature_schema JSON NOT NULL,
  trained_at DATETIME(6) NOT NULL,
  training_rows INT NOT NULL,
  positive_rate DECIMAL(8,6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (model_id),
  UNIQUE KEY uk_model_version (model_version)
) ENGINE=InnoDB COMMENT='v2：训练模型版本；分数仍不是真实欺诈概率';

CREATE TABLE IF NOT EXISTS model_evaluation (
  eval_id BIGINT NOT NULL AUTO_INCREMENT,
  model_version VARCHAR(64) NOT NULL,
  source_system VARCHAR(32) NOT NULL,
  split_strategy VARCHAR(32) NOT NULL COMMENT 'TIME_70_15_15',
  entity_grouping VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT 'CARD_ADDR_PURGED',
  valid_rows_dropped INT NOT NULL DEFAULT 0,
  test_rows_dropped INT NOT NULL DEFAULT 0,
  train_rows INT NOT NULL,
  valid_rows INT NOT NULL,
  test_rows INT NOT NULL,
  test_positives INT NOT NULL,
  base_rate DECIMAL(8,6) NOT NULL,
  pr_auc DECIMAL(8,6) NULL,
  roc_auc DECIMAL(8,6) NULL,
  recall_at_fpr_1 DECIMAL(8,6) NULL,
  recall_at_fpr_5 DECIMAL(8,6) NULL,
  precision_at_100 DECIMAL(8,6) NULL,
  brier DECIMAL(8,6) NULL,
  threshold DECIMAL(8,6) NULL,
  confusion_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (eval_id),
  KEY idx_eval_model (model_version, created_at)
) ENGINE=InnoDB COMMENT='v2：时间/实体切分与不平衡指标；不是生产有效性证明';

-- ---------- 6. IEEE-CIS 独立评测域（绝不与 dwd_order 关联） ----------
CREATE TABLE IF NOT EXISTS eval_ieee_transaction (
  transaction_id BIGINT NOT NULL,
  source_system VARCHAR(32) NOT NULL DEFAULT 'IEEE_CIS',
  dataset_name VARCHAR(64) NOT NULL,
  transaction_dt BIGINT NOT NULL COMMENT '相对时间，不伪造为日期',
  transaction_amt DECIMAL(18,2) NOT NULL,
  is_fraud TINYINT NOT NULL COMMENT '仅该数据集标签，不是 Olist 标签',
  feature_json JSON NOT NULL COMMENT '选定的匿名/数值特征；不含原始身份字段',
  import_batch_id VARCHAR(64) NOT NULL,
  imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (transaction_id),
  KEY idx_eval_ieee_time (transaction_dt),
  CONSTRAINT ck_eval_ieee_label CHECK (is_fraud IN (0,1)),
  CONSTRAINT ck_eval_ieee_amt CHECK (transaction_amt >= 0)
) ENGINE=InnoDB COMMENT='v2：IEEE-CIS 交易级评测，独立于订单数仓';

-- 说明：
-- 1. 本迁移不把 Olist 或 IEEE-CIS 的记录写入 ads_risk_alert/review。
-- 2. 不新增“欺诈真值”到订单表；订单表仍无 is_fraud 字段。
-- 3. 模型产物路径不进入 Git；数据库只存路径与元数据。
