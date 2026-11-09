-- MySQL 8.0+；只用于 SYNTHETIC 虚构演示。手动按版本顺序执行，无自动生产迁移。
CREATE DATABASE IF NOT EXISTS fraud_demo
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE fraud_demo;

CREATE TABLE IF NOT EXISTS ods_order_raw (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_system VARCHAR(32) NOT NULL,
  source_record_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  customer_ref VARCHAR(64) NOT NULL,
  customer_email VARCHAR(254) NULL COMMENT '仅虚构邮箱；真实敏感明文禁止',
  order_status VARCHAR(32) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL,
  payment_count INT NOT NULL,
  account_created_at DATETIME(6) NOT NULL COMMENT 'UTC',
  ordered_at DATETIME(6) NOT NULL COMMENT 'UTC',
  transaction_count_24h INT NOT NULL,
  ingest_batch_id VARCHAR(64) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  raw_payload JSON NOT NULL,
  ingested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT uk_ods_source_record UNIQUE (source_system, source_record_id),
  CONSTRAINT ck_ods_synthetic CHECK (source_system = 'SYNTHETIC'),
  CONSTRAINT ck_ods_amount CHECK (amount > 0),
  CONSTRAINT ck_ods_payment_count CHECK (payment_count >= 1),
  CONSTRAINT ck_ods_velocity CHECK (transaction_count_24h >= 1),
  CONSTRAINT ck_ods_dates CHECK (account_created_at <= ordered_at),
  CONSTRAINT ck_ods_hash CHECK (payload_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB COMMENT='ODS：虚构原始订单，单库逻辑分层';

CREATE TABLE IF NOT EXISTS dwd_order (
  order_id VARCHAR(64) NOT NULL,
  source_system VARCHAR(32) NOT NULL,
  source_record_id VARCHAR(64) NOT NULL,
  ingest_batch_id VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  customer_ref VARCHAR(64) NOT NULL,
  customer_email_masked VARCHAR(254) NULL COMMENT '展示掩码，不是加密',
  status VARCHAR(32) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL,
  payment_count INT NOT NULL,
  account_created_at DATETIME(6) NOT NULL COMMENT 'UTC',
  ordered_at DATETIME(6) NOT NULL COMMENT 'UTC',
  transaction_count_24h INT NOT NULL,
  data_origin VARCHAR(16) NOT NULL DEFAULT 'SYNTHETIC',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (order_id),
  CONSTRAINT uk_dwd_order_no UNIQUE (order_no),
  CONSTRAINT ck_dwd_origin CHECK (data_origin = 'SYNTHETIC' AND source_system = 'SYNTHETIC'),
  CONSTRAINT ck_dwd_status CHECK (status IN ('CREATED','PAID','CANCELLED')),
  CONSTRAINT ck_dwd_currency CHECK (currency = 'CNY'),
  CONSTRAINT ck_dwd_amount CHECK (amount > 0),
  CONSTRAINT ck_dwd_payment_count CHECK (payment_count >= 1),
  CONSTRAINT ck_dwd_velocity CHECK (transaction_count_24h >= 1),
  CONSTRAINT ck_dwd_dates CHECK (account_created_at <= ordered_at)
) ENGINE=InnoDB COMMENT='DWD：标准虚构订单；状态不是欺诈标签';

CREATE TABLE IF NOT EXISTS dws_order_risk_feature (
  feature_id BIGINT NOT NULL AUTO_INCREMENT,
  order_id VARCHAR(64) NOT NULL,
  feature_version VARCHAR(32) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL,
  payment_count INT NOT NULL,
  account_age_days INT NOT NULL,
  transaction_count_24h INT NOT NULL,
  as_of_at DATETIME(6) NOT NULL COMMENT 'UTC；不得使用此时间之后的数据',
  source_batch_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (feature_id),
  CONSTRAINT uk_dws_order_version UNIQUE (order_id, feature_version),
  CONSTRAINT fk_dws_order FOREIGN KEY (order_id) REFERENCES dwd_order(order_id),
  CONSTRAINT ck_dws_currency CHECK (currency = 'CNY'),
  CONSTRAINT ck_dws_amount CHECK (amount > 0),
  CONSTRAINT ck_dws_payment_count CHECK (payment_count >= 1),
  CONSTRAINT ck_dws_age CHECK (account_age_days >= 0),
  CONSTRAINT ck_dws_velocity CHECK (transaction_count_24h >= 1)
) ENGINE=InnoDB COMMENT='DWS：可复现评分特征快照';

CREATE TABLE IF NOT EXISTS ads_risk_score (
  score_id BIGINT NOT NULL AUTO_INCREMENT,
  order_id VARCHAR(64) NOT NULL,
  feature_id BIGINT NOT NULL,
  score DECIMAL(5,4) NOT NULL,
  risk_level VARCHAR(8) NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  model_type VARCHAR(32) NOT NULL,
  feature_version VARCHAR(32) NOT NULL,
  threshold DECIMAL(5,4) NOT NULL,
  rule_codes JSON NOT NULL,
  scored_at DATETIME(6) NOT NULL COMMENT 'UTC',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (score_id),
  CONSTRAINT fk_score_order FOREIGN KEY (order_id) REFERENCES dwd_order(order_id),
  CONSTRAINT fk_score_feature FOREIGN KEY (feature_id) REFERENCES dws_order_risk_feature(feature_id),
  CONSTRAINT ck_score_range CHECK (score BETWEEN 0 AND 1),
  CONSTRAINT ck_score_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT ck_score_threshold CHECK (threshold BETWEEN 0 AND 1),
  CONSTRAINT ck_score_model CHECK (model_type = 'DEMO_RULE')
) ENGINE=InnoDB COMMENT='ADS：演示评分，不是真实欺诈概率或标签';
CREATE INDEX idx_score_order_time ON ads_risk_score(order_id, scored_at);

CREATE TABLE IF NOT EXISTS ads_risk_alert (
  alert_id BIGINT NOT NULL AUTO_INCREMENT,
  order_id VARCHAR(64) NOT NULL,
  score_id BIGINT NOT NULL,
  alert_status VARCHAR(12) NOT NULL,
  risk_level VARCHAR(8) NOT NULL,
  reason_codes JSON NOT NULL,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (alert_id),
  CONSTRAINT uk_alert_score UNIQUE (score_id),
  CONSTRAINT fk_alert_order FOREIGN KEY (order_id) REFERENCES dwd_order(order_id),
  CONSTRAINT fk_alert_score FOREIGN KEY (score_id) REFERENCES ads_risk_score(score_id),
  CONSTRAINT ck_alert_status CHECK (alert_status IN ('OPEN','REVIEWING','RESOLVED')),
  CONSTRAINT ck_alert_level CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT ck_alert_version CHECK (version >= 1)
) ENGINE=InnoDB COMMENT='ADS：待复核风险信号，不等于欺诈事实';
CREATE INDEX idx_alert_status_time ON ads_risk_alert(alert_status, created_at);
CREATE INDEX idx_alert_order ON ads_risk_alert(order_id);

CREATE TABLE IF NOT EXISTS ads_alert_review (
  review_id BIGINT NOT NULL AUTO_INCREMENT,
  alert_id BIGINT NOT NULL,
  reviewer_id VARCHAR(64) NOT NULL,
  review_result VARCHAR(16) NOT NULL,
  comment VARCHAR(500) NULL,
  previous_status VARCHAR(12) NOT NULL,
  new_status VARCHAR(12) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (review_id),
  CONSTRAINT fk_review_alert FOREIGN KEY (alert_id) REFERENCES ads_risk_alert(alert_id),
  CONSTRAINT ck_review_result CHECK (review_result IN ('CONFIRMED','REJECTED','INCONCLUSIVE')),
  CONSTRAINT ck_review_old_status CHECK (previous_status IN ('OPEN','REVIEWING','RESOLVED')),
  CONSTRAINT ck_review_new_status CHECK (new_status IN ('OPEN','REVIEWING','RESOLVED'))
) ENGINE=InnoDB COMMENT='ADS：追加式虚构复核审计';
CREATE INDEX idx_review_alert_time ON ads_alert_review(alert_id, created_at);
