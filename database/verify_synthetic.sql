-- 应用账号只需 DML；DDL 由独立管理账号执行。
-- 索引存在性用 information_schema.statistics 检查，重复执行 V2 不是幂等操作。
SELECT 'ods_order_raw' AS table_name, COUNT(*) AS row_count FROM ods_order_raw
UNION ALL SELECT 'dwd_order', COUNT(*) FROM dwd_order
UNION ALL SELECT 'dws_order_risk_feature', COUNT(*) FROM dws_order_risk_feature
UNION ALL SELECT 'ads_risk_score', COUNT(*) FROM ads_risk_score
UNION ALL SELECT 'ads_risk_alert', COUNT(*) FROM ads_risk_alert
UNION ALL SELECT 'ads_alert_review', COUNT(*) FROM ads_alert_review;

SELECT order_id, feature_version, amount, payment_count, account_age_days, transaction_count_24h
FROM dws_order_risk_feature ORDER BY feature_id;

SELECT alert_id, alert_status, version, reason_codes FROM ads_risk_alert;
