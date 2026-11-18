package com.example.fraud;

import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated, prefix-scoped fixture for the M4 MySQL verification. */
final class M4MysqlFixture {
    static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    static final String PREFIX = "M4IT-" + RUN_ID;
    static final String CONFIG_CODE = "M4IT_" + RUN_ID.toUpperCase();
    static final Instant BASE = Instant.parse("2026-01-05T00:00:00Z");
    static final int TERMINAL_IMPORTS = 21;
    static final int RETURNED_IMPORTS = 20;
    static final int FIXTURE_ORDERS_IN_RANGE = 3;
    static final int FIXTURE_ALERTS = 2;
    static final int FIXTURE_WORK_ORDERS = 2;
    static final int FIXTURE_REVIEWS_RESOLVED = 1;
    static final long FIXTURE_DQ_TOTAL = 4L;
    static final long FIXTURE_DQ_EVALUATED = 3L;
    static final double FIXTURE_DQ_PASS_RATE = 0.6667d;
    static final double FIXTURE_OLIST_PASS_RATE = 0.5d;
    static final double FIXTURE_IEEE_PASS_RATE = 1.0d;

    private M4MysqlFixture() {
    }

    static void applySchema(JdbcTemplate jdbc) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.addScript(resource("V1__init.sql"));
        populator.addScript(resource("V3__v2_sources_eval.sql"));
        populator.addScript(resource("V4__v3_work_orders.sql"));
        populator.addScript(resource("V5__v3_ops_events.sql"));
        DatabasePopulatorUtils.execute(populator, jdbc.getDataSource());
    }

    static void assertSchema(JdbcTemplate jdbc) {
        assertTable(jdbc, "dwd_order");
        assertTable(jdbc, "ads_risk_alert");
        assertTable(jdbc, "ads_alert_review");
        assertTable(jdbc, "etl_import_batch");
        assertTable(jdbc, "dq_check_result");
        assertTable(jdbc, "work_order_config");
        assertTable(jdbc, "work_order_config_version");
        assertTable(jdbc, "work_order");
        assertTable(jdbc, "work_order_event");
        assertTable(jdbc, "ops_log_event");
        assertColumn(jdbc, "dwd_order", "data_origin");
        assertColumn(jdbc, "ads_alert_review", "new_status");
        assertColumn(jdbc, "etl_import_batch", "started_at");
        assertColumn(jdbc, "dq_check_result", "passed");
        assertColumn(jdbc, "work_order", "status");
        assertForeignKey(jdbc, "dq_check_result", "fk_dq_batch");
        assertForeignKey(jdbc, "work_order", "fk_wo_alert");
        assertUniqueIndex(jdbc, "etl_import_batch", "uk_import_idempotency");
        assertUniqueIndex(jdbc, "work_order", "uk_wo_alert");
        assertConstraint(jdbc, "work_order", "ck_wo_status");
    }

    static void insert(JdbcTemplate jdbc) {
        insertOrder(jdbc, "O-OLIST-MID", "OLIST", "BRL", BASE);
        insertOrder(jdbc, "O-OLIST-END", "OLIST", "BRL",
                BASE.plusSeconds(86399).plusMillis(999));
        insertOrder(jdbc, "O-SYNTHETIC", "SYNTHETIC", "CNY", BASE.plusSeconds(43200));
        insertOrder(jdbc, "O-EXCLUSIVE", "OLIST", "BRL", BASE.plusSeconds(86400));

        long highAlert = insertAlert(jdbc, "O-OLIST-MID", "HIGH", "0.9100");
        long lowAlert = insertAlert(jdbc, "O-OLIST-END", "LOW", "0.2100");
        long resolvedReview = insertReview(jdbc, highAlert, "INCONCLUSIVE", "RESOLVED");
        insertReview(jdbc, lowAlert, "INCONCLUSIVE", "REVIEWING");

        long configId = insertConfig(jdbc);
        long versionId = insertConfigVersion(jdbc, configId);
        jdbc.update("UPDATE work_order_config SET published_version=1 WHERE id=?", configId);
        insertWorkOrder(jdbc, highAlert, configId, versionId, "RESOLVED", resolvedReview);
        insertWorkOrder(jdbc, lowAlert, configId, versionId, "OPEN", null);

        for (int i = 0; i < TERMINAL_IMPORTS; i++) {
            insertImport(jdbc, i, i < 19 ? BASE.plusSeconds(i * 60L) : BASE.plusSeconds(1800));
        }
        insertImport(jdbc, 21, BASE.plusSeconds(2400), "RUNNING");
        insertImport(jdbc, 22, BASE.plusSeconds(27L * 86400L), "SUCCEEDED");
        insertDq(jdbc, "B-00", "ERROR", true);
        insertDq(jdbc, "B-00", "WARN", false);
        insertDq(jdbc, "B-00", "INFO", false);
        insertDq(jdbc, "B-01", "WARN", true);
        insertDq(jdbc, "B-22", "INFO", true);
    }

    static void cleanup(JdbcTemplate jdbc) {
        String like = PREFIX + "%";
        jdbc.update("DELETE FROM work_order_event WHERE work_order_id IN " +
                "(SELECT id FROM work_order WHERE created_by LIKE ?)", like);
        jdbc.update("DELETE FROM work_order WHERE created_by LIKE ?", like);
        jdbc.update("UPDATE work_order_config SET published_version=NULL WHERE code LIKE ?", like);
        jdbc.update("DELETE FROM work_order_config_version WHERE code LIKE ?", like);
        jdbc.update("DELETE FROM work_order_config WHERE code LIKE ?", like);
        jdbc.update("DELETE FROM ads_alert_review WHERE reviewer_id LIKE ?", like);
        jdbc.update("DELETE FROM ads_risk_alert WHERE order_id LIKE ?", like);
        jdbc.update("DELETE FROM ads_risk_score WHERE order_id LIKE ?", like);
        jdbc.update("DELETE FROM dws_order_risk_feature WHERE order_id LIKE ?", like);
        jdbc.update("DELETE FROM dwd_order WHERE order_id LIKE ?", like);
        jdbc.update("DELETE FROM dq_check_result WHERE batch_id LIKE ?", like);
        jdbc.update("DELETE FROM etl_import_batch WHERE batch_id LIKE ?", like);
        jdbc.update("DELETE FROM ops_log_event WHERE task_id LIKE ? OR actor_id LIKE ?", like, like);
    }

    static void assertClean(JdbcTemplate jdbc) {
        String like = PREFIX + "%";
        assertZero(jdbc, "SELECT COUNT(*) FROM work_order_event WHERE work_order_id IN " +
                "(SELECT id FROM work_order WHERE created_by LIKE ?)", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM work_order WHERE created_by LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM work_order_config_version WHERE code LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM work_order_config WHERE code LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM ads_alert_review WHERE reviewer_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM ads_risk_alert WHERE order_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM ads_risk_score WHERE order_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM dws_order_risk_feature WHERE order_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM dwd_order WHERE order_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM dq_check_result WHERE batch_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM etl_import_batch WHERE batch_id LIKE ?", like);
        assertZero(jdbc, "SELECT COUNT(*) FROM ops_log_event WHERE task_id LIKE ? OR actor_id LIKE ?",
                like, like);
    }

    private static void assertZero(JdbcTemplate jdbc, String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        assertTrue(count == null || count == 0L, "fixture rows remain: " + sql);
    }

    private static void insertOrder(JdbcTemplate jdbc, String suffix, String origin,
                                    String currency, Instant orderedAt) {
        String orderId = PREFIX + "-" + suffix;
        jdbc.update("INSERT INTO dwd_order(order_id,source_system,source_record_id," +
                        "ingest_batch_id,order_no,customer_ref,customer_email_masked,status," +
                        "amount,currency,payment_count,account_created_at,ordered_at," +
                        "transaction_count_24h,data_origin,dataset_name,source_status) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderId, origin, orderId, PREFIX + "-BATCH", orderId + "-NO",
                orderId + "-CUSTOMER", "fixture@example.invalid", "PAID",
                100.00d, currency, 1, Timestamp.from(orderedAt.minusSeconds(86400)),
                Timestamp.from(orderedAt), 1, origin, "m4-fixture", "PAID");
    }

    private static long insertAlert(JdbcTemplate jdbc, String orderSuffix, String riskLevel,
                                    String scoreValue) {
        String orderId = PREFIX + "-" + orderSuffix;
        String currency = "O-SYNTHETIC".equals(orderSuffix) ? "CNY" : "BRL";
        jdbc.update("INSERT INTO dws_order_risk_feature(order_id,feature_version,amount," +
                        "currency,payment_count,account_age_days,transaction_count_24h,as_of_at," +
                        "source_batch_id) VALUES(?,?,?,?,?,?,?,?,?)",
                orderId, "M4IT-v1", 100.00d, currency, 1, 1, 1,
                Timestamp.from(BASE), PREFIX + "-BATCH");
        Long featureId = jdbc.queryForObject("SELECT feature_id FROM dws_order_risk_feature " +
                "WHERE order_id=? AND feature_version=?", Long.class, orderId, "M4IT-v1");
        jdbc.update("INSERT INTO ads_risk_score(order_id,feature_id,score,risk_level," +
                        "model_version,model_type,feature_version,threshold,rule_codes,scored_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?)",
                orderId, featureId, Double.valueOf(scoreValue), riskLevel, "M4IT-rule",
                "DEMO_RULE", "M4IT-v1", 0.7d, "[\"M4_IT\"]", Timestamp.from(BASE));
        Long scoreId = jdbc.queryForObject("SELECT score_id FROM ads_risk_score WHERE order_id=?",
                Long.class, orderId);
        jdbc.update("INSERT INTO ads_risk_alert(order_id,score_id,alert_status,risk_level," +
                        "reason_codes,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                orderId, scoreId, "OPEN", riskLevel, "[\"M4_IT\"]", 1,
                Timestamp.from(BASE.plusSeconds(28800)), Timestamp.from(BASE.plusSeconds(28800)));
        return jdbc.queryForObject("SELECT alert_id FROM ads_risk_alert WHERE score_id=?",
                Long.class, scoreId);
    }

    private static long insertReview(JdbcTemplate jdbc, long alertId, String result,
                                     String newStatus) {
        jdbc.update("INSERT INTO ads_alert_review(alert_id,reviewer_id,review_result,comment," +
                        "previous_status,new_status,created_at) VALUES(?,?,?,?,?,?,?)",
                alertId, PREFIX + "-REVIEWER", result, "synthetic M4 fixture",
                "REVIEWING", newStatus, Timestamp.from(BASE.plusSeconds(32400)));
        return jdbc.queryForObject("SELECT review_id FROM ads_alert_review " +
                "WHERE alert_id=? ORDER BY review_id DESC LIMIT 1", Long.class, alertId);
    }

    private static long insertConfig(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO work_order_config(code,name,description,priority,trigger_json," +
                        "risk_score_threshold,sla_minutes,requires_review,assign_role,status," +
                        "version,published_version,created_by,updated_by,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                CONFIG_CODE, "M4 fixture config", "synthetic", 3,
                "{\"sourceSystems\":[\"OLIST\"],\"riskLevels\":[\"HIGH\",\"LOW\"]," +
                        "\"alertStatuses\":[\"OPEN\"],\"reasonCodes\":[]}",
                0.3d, 120, 0, "REVIEWER", "ACTIVE", 1, null,
                PREFIX + "-ADMIN", PREFIX + "-ADMIN", Timestamp.from(BASE),
                Timestamp.from(BASE));
        return jdbc.queryForObject("SELECT id FROM work_order_config WHERE code=?",
                Long.class, CONFIG_CODE);
    }

    private static long insertConfigVersion(JdbcTemplate jdbc, long configId) {
        jdbc.update("INSERT INTO work_order_config_version(config_id,version_no,source_version," +
                        "code,name,description,priority,trigger_json,risk_score_threshold," +
                        "sla_minutes,requires_review,assign_role,published_by,published_at," +
                        "created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                configId, 1, 1, CONFIG_CODE, "M4 fixture config", "synthetic", 3,
                "{\"sourceSystems\":[\"OLIST\"],\"riskLevels\":[\"HIGH\",\"LOW\"]," +
                        "\"alertStatuses\":[\"OPEN\"],\"reasonCodes\":[]}",
                0.3d, 120, 0, "REVIEWER", PREFIX + "-ADMIN", Timestamp.from(BASE),
                Timestamp.from(BASE));
        return jdbc.queryForObject("SELECT id FROM work_order_config_version WHERE config_id=?",
                Long.class, configId);
    }

    private static void insertWorkOrder(JdbcTemplate jdbc, long alertId, long configId,
                                         long versionId, String status, Long reviewId) {
        Timestamp created = Timestamp.from(BASE.plusSeconds(3600));
        Timestamp resolved = "RESOLVED".equals(status)
                ? Timestamp.from(BASE.plusSeconds(7200)) : null;
        jdbc.update("INSERT INTO work_order(alert_id,config_id,config_version_id," +
                        "config_version_no,config_snapshot_json,assign_role,assignee_id,status," +
                        "requires_review,sla_minutes,due_at,resolution_review_id,resolved_at," +
                        "cancelled_at,version,created_by,updated_by,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                alertId, configId, versionId, 1,
                "{\"code\":\"" + CONFIG_CODE + "\"}", "REVIEWER",
                "RESOLVED".equals(status) ? PREFIX + "-REVIEWER" : null,
                status, 0, 120, Timestamp.from(BASE.plusSeconds(14400)), reviewId,
                resolved, null, 1, PREFIX + "-ADMIN", PREFIX + "-ADMIN", created, created);
    }

    private static void insertImport(JdbcTemplate jdbc, int index, Instant startedAt) {
        insertImport(jdbc, index, startedAt, null);
    }

    private static void insertImport(JdbcTemplate jdbc, int index, Instant startedAt,
                                     String forcedStatus) {
        String status = forcedStatus != null ? forcedStatus
                : index == 2 ? "PARTIAL" : index == 3 ? "FAILED" : "SUCCEEDED";
        String source = index == 1 ? "IEEE_CIS"
                : index == 20 ? "NO_DQ_SOURCE"
                : index == 22 ? "INFO_ONLY"
                : "OLIST";
        String batchId = String.format("%s-B-%02d", PREFIX, index);
        jdbc.update("INSERT INTO etl_import_batch(batch_id,source_system,dataset_name," +
                        "idempotency_key,status,total_rows,accepted_rows,rejected_rows,file_count," +
                        "operator_id,error_summary,started_at,finished_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batchId, source, "m4-fixture", String.format("%064d", index), status,
                10, status.equals("FAILED") ? 0 : 10, status.equals("FAILED") ? 10 : 0,
                1, PREFIX + "-ADMIN", null, Timestamp.from(startedAt), Timestamp.from(startedAt));
    }

    private static void insertDq(JdbcTemplate jdbc, String suffix, String severity, boolean passed) {
        jdbc.update("INSERT INTO dq_check_result(batch_id,check_code,severity,passed," +
                        "observed,expected,detail,created_at) VALUES(?,?,?,?,?,?,?,?)",
                PREFIX + "-" + suffix, "M4IT_CHECK", severity, passed ? 1 : 0,
                "fixture", "fixture", "synthetic", Timestamp.from(BASE));
    }

    private static void assertTable(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",
                Integer.class, table);
        assertEquals(Integer.valueOf(1), count, "missing table " + table);
    }

    private static void assertColumn(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",
                Integer.class, table, column);
        assertEquals(Integer.valueOf(1), count, "missing column " + table + "." + column);
    }

    private static void assertForeignKey(JdbcTemplate jdbc, String table, String name) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? " +
                        "AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE='FOREIGN KEY'",
                Integer.class, table, name);
        assertEquals(Integer.valueOf(1), count, "missing foreign key " + name);
    }

    private static void assertUniqueIndex(JdbcTemplate jdbc, String table, String name) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? " +
                        "AND NON_UNIQUE=0", Integer.class, table, name);
        assertTrue(count != null && count > 0, "missing unique index " + name);
    }

    private static void assertConstraint(JdbcTemplate jdbc, String table, String name) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? " +
                        "AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE='CHECK'",
                Integer.class, table, name);
        assertEquals(Integer.valueOf(1), count, "missing check " + name);
    }

    private static FileSystemResource resource(String name) {
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File directory = current; directory != null; directory = directory.getParentFile()) {
            File candidate = new File(directory, "database/migrations/" + name);
            if (candidate.isFile()) {
                return new FileSystemResource(candidate);
            }
        }
        throw new IllegalStateException("Cannot locate database/migrations/" + name
                + " from " + current);
    }
}
