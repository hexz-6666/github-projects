-- 全部虚构；不得替换为真实个人信息。仅用于初始化空白演示库。
USE fraud_demo;

INSERT INTO ods_order_raw (
  source_system, source_record_id, order_no, customer_ref, customer_email,
  order_status, amount, currency, payment_count, account_created_at, ordered_at,
  transaction_count_24h, ingest_batch_id, payload_hash, raw_payload, ingested_at
) VALUES
('SYNTHETIC','DEMO-RAW-0001','DEMO-N-0001','DEMO-C-0001','demo-customer-0001@example.invalid',
 'PAID',1200.00,'CNY',3,'2026-01-01 00:00:00','2026-01-03 08:00:00',6,'DEMO-BATCH-V1',
 SHA2('DEMO-RAW-0001|v1',256),JSON_OBJECT('origin','SYNTHETIC','schemaVersion',1),'2026-01-03 08:01:00'),
('SYNTHETIC','DEMO-RAW-0002','DEMO-N-0002','DEMO-C-0002','demo-customer-0002@example.invalid',
 'PAID',680.00,'CNY',1,'2025-10-01 00:00:00','2026-01-03 09:00:00',1,'DEMO-BATCH-V1',
 SHA2('DEMO-RAW-0002|v1',256),JSON_OBJECT('origin','SYNTHETIC','schemaVersion',1),'2026-01-03 09:01:00'),
('SYNTHETIC','DEMO-RAW-0003','DEMO-N-0003','DEMO-C-0003','demo-customer-0003@example.invalid',
 'CREATED',1200.00,'CNY',1,'2025-12-01 00:00:00','2026-01-03 10:00:00',1,'DEMO-BATCH-V1',
 SHA2('DEMO-RAW-0003|v1',256),JSON_OBJECT('origin','SYNTHETIC','schemaVersion',1),'2026-01-03 10:01:00');

INSERT INTO dwd_order (
  order_id,source_system,source_record_id,ingest_batch_id,order_no,customer_ref,
  customer_email_masked,status,amount,currency,payment_count,account_created_at,
  ordered_at,transaction_count_24h,data_origin,created_at,updated_at
) VALUES
('DEMO-O-0001','SYNTHETIC','DEMO-RAW-0001','DEMO-BATCH-V1','DEMO-N-0001','DEMO-C-0001',
 'demo-****@example.invalid','PAID',1200.00,'CNY',3,'2026-01-01 00:00:00','2026-01-03 08:00:00',6,'SYNTHETIC','2026-01-03 08:02:00','2026-01-03 08:02:00'),
('DEMO-O-0002','SYNTHETIC','DEMO-RAW-0002','DEMO-BATCH-V1','DEMO-N-0002','DEMO-C-0002',
 'demo-****@example.invalid','PAID',680.00,'CNY',1,'2025-10-01 00:00:00','2026-01-03 09:00:00',1,'SYNTHETIC','2026-01-03 09:02:00','2026-01-03 09:02:00'),
('DEMO-O-0003','SYNTHETIC','DEMO-RAW-0003','DEMO-BATCH-V1','DEMO-N-0003','DEMO-C-0003',
 'demo-****@example.invalid','CREATED',1200.00,'CNY',1,'2025-12-01 00:00:00','2026-01-03 10:00:00',1,'SYNTHETIC','2026-01-03 10:02:00','2026-01-03 10:02:00');

INSERT INTO dws_order_risk_feature (
  feature_id,order_id,feature_version,amount,currency,payment_count,account_age_days,
  transaction_count_24h,as_of_at,source_batch_id,created_at
) VALUES
(1,'DEMO-O-0001','synthetic-feature-v1',1200.00,'CNY',3,2,6,'2026-01-03 08:00:00','DEMO-BATCH-V1','2026-01-03 08:03:00'),
(2,'DEMO-O-0002','synthetic-feature-v1',680.00,'CNY',1,94,1,'2026-01-03 09:00:00','DEMO-BATCH-V1','2026-01-03 09:03:00'),
(3,'DEMO-O-0003','synthetic-feature-v1',1200.00,'CNY',1,33,1,'2026-01-03 10:00:00','DEMO-BATCH-V1','2026-01-03 10:03:00');

-- 此条为明确标注的手工虚构种子，不代表调用过模型服务。
INSERT INTO ads_risk_score (
  score_id,order_id,feature_id,score,risk_level,model_version,model_type,
  feature_version,threshold,rule_codes,scored_at,created_at
) VALUES
(1,'DEMO-O-0001',1,0.9900,'HIGH','synthetic-rule-v1','DEMO_RULE','synthetic-feature-v1',0.7000,
 JSON_ARRAY('HIGH_AMOUNT','REPEATED_PAYMENT_ATTEMPTS','NEW_ACCOUNT','HIGH_VELOCITY'),'2026-01-03 08:04:00','2026-01-03 08:04:00');

INSERT INTO ads_risk_alert (alert_id,order_id,score_id,alert_status,risk_level,reason_codes,version,created_at,updated_at)
VALUES (1,'DEMO-O-0001',1,'OPEN','HIGH',
 JSON_ARRAY('HIGH_AMOUNT','REPEATED_PAYMENT_ATTEMPTS','NEW_ACCOUNT','HIGH_VELOCITY'),1,'2026-01-03 08:05:00','2026-01-03 08:05:00');
