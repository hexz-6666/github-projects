package com.example.fraud.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("mysql")
public class MysqlV2Store implements V2Store {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MysqlV2Store(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String storageMode() { return "mysql"; }

    @Override
    public void appendAudit(Map<String, Object> event) {
        jdbc.update("INSERT INTO audit_event(actor_id,actor_role,action,target_type,target_id," +
                        "result,request_id,detail_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                event.get("actorId"), event.get("actorRole"), event.get("action"),
                event.get("targetType"), event.get("targetId"), event.get("result"),
                event.get("requestId"), json(event.get("detail")),
                Timestamp.from(instant(event.get("createdAt"))));
    }

    @Override
    public List<Map<String, Object>> findAudit(int offset, int size) {
        return jdbc.query("SELECT event_id,actor_id,actor_role,action,target_type,target_id," +
                        "result,request_id,detail_json,created_at FROM audit_event " +
                        "ORDER BY created_at DESC,event_id DESC LIMIT ? OFFSET ?",
                auditMapper(), size, offset);
    }

    @Override
    public long countAudit() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<Map<String, Object>> findImportByKey(String idempotencyKey) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM etl_import_batch WHERE idempotency_key=?",
                importMapper(), idempotencyKey);
        return rows.stream().findFirst();
    }

    @Override
    public void insertImport(Map<String, Object> batch) {
        jdbc.update("INSERT INTO etl_import_batch(batch_id,source_system,dataset_name,idempotency_key," +
                        "status,total_rows,accepted_rows,rejected_rows,file_count,operator_id," +
                        "error_summary,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batch.get("batchId"), batch.get("sourceSystem"), batch.get("datasetName"),
                batch.get("idempotencyKey"), batch.get("status"), batch.get("totalRows"),
                batch.get("acceptedRows"), batch.get("rejectedRows"), batch.get("fileCount"),
                batch.get("operatorId"), batch.get("errorSummary"),
                Timestamp.from(instant(batch.get("startedAt"))),
                batch.get("finishedAt") == null ? null : Timestamp.from(instant(batch.get("finishedAt"))));
    }

    @Override
    public void updateImport(Map<String, Object> batch) {
        jdbc.update("UPDATE etl_import_batch SET status=?,total_rows=?,accepted_rows=?," +
                        "rejected_rows=?,error_summary=?,finished_at=? WHERE batch_id=?",
                batch.get("status"), batch.get("totalRows"), batch.get("acceptedRows"),
                batch.get("rejectedRows"), batch.get("errorSummary"),
                batch.get("finishedAt") == null ? null : Timestamp.from(instant(batch.get("finishedAt"))),
                batch.get("batchId"));
    }

    @Override
    public Optional<Map<String, Object>> findImport(String batchId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM etl_import_batch WHERE batch_id=?",
                importMapper(), batchId);
        return rows.stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> findImports(int offset, int size) {
        return jdbc.query("SELECT * FROM etl_import_batch ORDER BY started_at DESC LIMIT ? OFFSET ?",
                importMapper(), size, offset);
    }

    @Override
    public long countImports() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM etl_import_batch", Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public void saveDqChecks(List<Map<String, Object>> checks) {
        for (Map<String, Object> check : checks) {
            jdbc.update("INSERT INTO dq_check_result(batch_id,check_code,severity,passed,observed," +
                            "expected,detail) VALUES(?,?,?,?,?,?,?)",
                    check.get("batchId"), check.get("checkCode"), check.get("severity"),
                    Boolean.TRUE.equals(check.get("passed")) ? 1 : 0,
                    check.get("observed"), check.get("expected"), check.get("detail"));
        }
    }

    @Override
    public List<Map<String, Object>> findDqChecks(String batchId) {
        return jdbc.query("SELECT batch_id,check_code,severity,passed,observed,expected,detail,created_at " +
                        "FROM dq_check_result WHERE batch_id=? ORDER BY check_id",
                dqMapper(), batchId);
    }

    @Override
    public void upsertOlistOrder(Map<String, Object> row) {
        String raw = String.valueOf(row.get("rawPayload"));
        Timestamp orderedAt = Timestamp.from(instant(row.get("orderedAt")));
        jdbc.update("INSERT INTO ods_order_raw(source_system,source_record_id,order_no,customer_ref," +
                        "customer_email,order_status,amount,currency,payment_count,account_created_at," +
                        "ordered_at,transaction_count_24h,ingest_batch_id,payload_hash,raw_payload," +
                        "dataset_name,license_ref,source_status) " +
                        "VALUES('OLIST',?,?,?,NULL,?,?,?,?,NULL,?,NULL,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE order_status=VALUES(order_status),amount=VALUES(amount)," +
                        "payment_count=VALUES(payment_count),ingest_batch_id=VALUES(ingest_batch_id)," +
                        "payload_hash=VALUES(payload_hash),raw_payload=VALUES(raw_payload)," +
                        "dataset_name=VALUES(dataset_name),source_status=VALUES(source_status)",
                row.get("sourceRecordId"), row.get("orderNo"), row.get("customerRef"),
                row.get("sourceStatus"), row.get("amount"), row.get("currency"),
                row.get("paymentCount"), orderedAt, row.get("batchId"), row.get("payloadHash"),
                raw, row.get("datasetName"), row.get("licenseRef"), row.get("sourceStatus"));
        jdbc.update("INSERT INTO dwd_order(order_id,source_system,source_record_id,ingest_batch_id," +
                        "order_no,customer_ref,customer_email_masked,status,amount,currency," +
                        "payment_count,account_created_at,ordered_at,transaction_count_24h," +
                        "data_origin,dataset_name,source_status) " +
                        "VALUES(?,'OLIST',?,?,?,?,NULL,?,?,?,?,NULL,?,NULL,'OLIST',?,?) " +
                        "ON DUPLICATE KEY UPDATE status=VALUES(status),amount=VALUES(amount)," +
                        "payment_count=VALUES(payment_count),ingest_batch_id=VALUES(ingest_batch_id)," +
                        "dataset_name=VALUES(dataset_name),source_status=VALUES(source_status)",
                row.get("orderId"), row.get("sourceRecordId"), row.get("batchId"),
                row.get("orderNo"), row.get("customerRef"), row.get("status"),
                row.get("amount"), row.get("currency"), row.get("paymentCount"), orderedAt,
                row.get("datasetName"), row.get("sourceStatus"));
    }

    @Override
    public void upsertIeeeTransaction(Map<String, Object> row) {
        jdbc.update("INSERT INTO eval_ieee_transaction(transaction_id,dataset_name,transaction_dt," +
                        "transaction_amt,is_fraud,feature_json,import_batch_id) VALUES(?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE transaction_amt=VALUES(transaction_amt)," +
                        "is_fraud=VALUES(is_fraud),feature_json=VALUES(feature_json)," +
                        "import_batch_id=VALUES(import_batch_id)",
                row.get("transactionId"), row.get("datasetName"), row.get("transactionDt"),
                row.get("transactionAmt"), row.get("isFraud"), json(row.get("featureJson")),
                row.get("batchId"));
    }

    @Override
    public long countIeeeTransactions() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM eval_ieee_transaction", Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public void saveModel(Map<String, Object> model) {
        jdbc.update("INSERT INTO model_registry(model_version,model_type,source_system,artifact_path," +
                        "feature_schema,trained_at,training_rows,positive_rate,status) " +
                        "VALUES(?,?,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE model_type=VALUES(model_type)," +
                        "artifact_path=VALUES(artifact_path),feature_schema=VALUES(feature_schema)," +
                        "trained_at=VALUES(trained_at),training_rows=VALUES(training_rows)," +
                        "positive_rate=VALUES(positive_rate),status=VALUES(status)",
                model.get("modelVersion"), model.get("modelType"), model.get("sourceSystem"),
                model.get("artifactPath"), json(model.get("featureSchema")),
                Timestamp.from(instant(model.get("trainedAt"))), model.get("trainingRows"),
                model.get("positiveRate"), model.get("status"));
    }

    @Override
    public List<Map<String, Object>> findModels() {
        return jdbc.query("SELECT model_id,model_version,model_type,source_system,artifact_path," +
                        "feature_schema,trained_at,training_rows,positive_rate,status,created_at " +
                        "FROM model_registry ORDER BY model_id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("modelId", rs.getLong("model_id"));
                    row.put("modelVersion", rs.getString("model_version"));
                    row.put("modelType", rs.getString("model_type"));
                    row.put("sourceSystem", rs.getString("source_system"));
                    row.put("artifactPath", rs.getString("artifact_path"));
                    row.put("featureSchema", readJson(rs.getString("feature_schema")));
                    row.put("trainedAt", rs.getTimestamp("trained_at").toInstant().toString());
                    row.put("trainingRows", rs.getInt("training_rows"));
                    row.put("positiveRate", rs.getBigDecimal("positive_rate"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                });
    }

    @Override
    public void saveEvaluation(Map<String, Object> evaluation) {
        jdbc.update("INSERT INTO model_evaluation(model_version,source_system,split_strategy," +
                        "entity_grouping,valid_rows_dropped,test_rows_dropped," +
                        "train_rows,valid_rows,test_rows,test_positives,base_rate,pr_auc,roc_auc," +
                        "recall_at_fpr_1,recall_at_fpr_5,precision_at_100,brier,threshold," +
                        "confusion_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evaluation.get("modelVersion"), evaluation.get("sourceSystem"),
                evaluation.get("splitStrategy"),
                evaluation.get("entityGrouping") == null ? "NONE" : evaluation.get("entityGrouping"),
                evaluation.get("validRowsDroppedForEntity") == null ? 0
                        : evaluation.get("validRowsDroppedForEntity"),
                evaluation.get("testRowsDroppedForEntity") == null ? 0
                        : evaluation.get("testRowsDroppedForEntity"),
                evaluation.get("trainRows"),
                evaluation.get("validRows"), evaluation.get("testRows"),
                evaluation.get("testPositives"), evaluation.get("baseRate"),
                evaluation.get("prAuc"), evaluation.get("rocAuc"),
                evaluation.get("recallAtFpr1"), evaluation.get("recallAtFpr5"),
                evaluation.get("precisionAt100"), evaluation.get("brier"),
                evaluation.get("threshold"), json(evaluation.get("confusion")));
    }

    @Override
    public List<Map<String, Object>> findEvaluations() {
        return jdbc.query("SELECT eval_id,model_version,source_system,split_strategy," +
                        "entity_grouping,valid_rows_dropped,test_rows_dropped,train_rows," +
                        "valid_rows,test_rows,test_positives,base_rate,pr_auc,roc_auc," +
                        "recall_at_fpr_1,recall_at_fpr_5,precision_at_100,brier,threshold," +
                        "confusion_json,created_at FROM model_evaluation ORDER BY eval_id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("evalId", rs.getLong("eval_id"));
                    row.put("modelVersion", rs.getString("model_version"));
                    row.put("sourceSystem", rs.getString("source_system"));
                    row.put("splitStrategy", rs.getString("split_strategy"));
                    row.put("entityGrouping", rs.getString("entity_grouping"));
                    row.put("validRowsDroppedForEntity", rs.getInt("valid_rows_dropped"));
                    row.put("testRowsDroppedForEntity", rs.getInt("test_rows_dropped"));
                    row.put("trainRows", rs.getInt("train_rows"));
                    row.put("validRows", rs.getInt("valid_rows"));
                    row.put("testRows", rs.getInt("test_rows"));
                    row.put("testPositives", rs.getInt("test_positives"));
                    row.put("baseRate", rs.getBigDecimal("base_rate"));
                    row.put("prAuc", rs.getBigDecimal("pr_auc"));
                    row.put("rocAuc", rs.getBigDecimal("roc_auc"));
                    row.put("recallAtFpr1", rs.getBigDecimal("recall_at_fpr_1"));
                    row.put("recallAtFpr5", rs.getBigDecimal("recall_at_fpr_5"));
                    row.put("precisionAt100", rs.getBigDecimal("precision_at_100"));
                    row.put("brier", rs.getBigDecimal("brier"));
                    row.put("threshold", rs.getBigDecimal("threshold"));
                    row.put("confusion", readJson(rs.getString("confusion_json")));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                });
    }

    private RowMapper<Map<String, Object>> auditMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("eventId", rs.getLong("event_id"));
            row.put("actorId", rs.getString("actor_id"));
            row.put("actorRole", rs.getString("actor_role"));
            row.put("action", rs.getString("action"));
            row.put("targetType", rs.getString("target_type"));
            row.put("targetId", rs.getString("target_id"));
            row.put("result", rs.getString("result"));
            row.put("requestId", rs.getString("request_id"));
            row.put("detail", readJson(rs.getString("detail_json")));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        };
    }

    private RowMapper<Map<String, Object>> importMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("batchId", rs.getString("batch_id"));
            row.put("sourceSystem", rs.getString("source_system"));
            row.put("datasetName", rs.getString("dataset_name"));
            row.put("idempotencyKey", rs.getString("idempotency_key"));
            row.put("status", rs.getString("status"));
            row.put("totalRows", rs.getInt("total_rows"));
            row.put("acceptedRows", rs.getInt("accepted_rows"));
            row.put("rejectedRows", rs.getInt("rejected_rows"));
            row.put("fileCount", rs.getInt("file_count"));
            row.put("operatorId", rs.getString("operator_id"));
            row.put("errorSummary", rs.getString("error_summary"));
            row.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
            Timestamp finished = rs.getTimestamp("finished_at");
            row.put("finishedAt", finished == null ? null : finished.toInstant().toString());
            return row;
        };
    }

    private RowMapper<Map<String, Object>> dqMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("batchId", rs.getString("batch_id"));
            row.put("checkCode", rs.getString("check_code"));
            row.put("severity", rs.getString("severity"));
            row.put("passed", rs.getInt("passed") == 1);
            row.put("observed", rs.getString("observed"));
            row.put("expected", rs.getString("expected"));
            row.put("detail", rs.getString("detail"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize metadata", exception);
        }
    }

    private Object readJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Object>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private Instant instant(Object value) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        return Instant.parse(String.valueOf(value));
    }
}
