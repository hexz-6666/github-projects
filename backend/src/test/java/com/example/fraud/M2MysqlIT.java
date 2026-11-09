package com.example.fraud;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gated MySQL integration checks. They never run in the normal Stub build.
 *
 * <p>Run only against a disposable/local MySQL database that already has
 * V1+V2+V3+V4 applied:</p>
 *
 * <pre>
 * $env:DB_URL='jdbc:mysql://127.0.0.1:3306/fraud_demo?...'
 * $env:DB_USERNAME='...'; $env:DB_PASSWORD='...'
 * mvn -q "-Dtest=M2MysqlIT" test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
@SpringBootTest
@ActiveProfiles("mysql")
class M2MysqlIT {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v4CompositeIndexesAndForeignKeysExist() {
        assertEquals(1, count("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ads_alert_review' " +
                "AND INDEX_NAME='uk_review_id_alert'"));
        assertEquals(1, count("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='work_order_config_version' " +
                "AND INDEX_NAME='uk_wocv_id_config_version'"));
        assertEquals(1, constraintCount("work_order", "fk_wo_resolution_review_alert"));
        assertEquals(1, constraintCount("work_order", "fk_wo_config_version_consistent"));
        assertEquals(1, constraintCount("work_order_config", "fk_woc_published_version"));
    }

    @Test
    @Transactional
    void mismatchedConfigVersionTripletIsRejectedByDatabase() {
        long configId = insertConfig("M2_IT_TRIPLET");
        long versionV1 = insertVersion(configId, 1);
        insertVersion(configId, 2);
        long alertId = firstAlertId();

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO work_order(alert_id,config_id,config_version_id,config_version_no," +
                        "config_snapshot_json,assign_role,assignee_id,status,requires_review,sla_minutes," +
                        "due_at,resolution_review_id,resolved_at,cancelled_at,version,created_by,updated_by," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'OPEN',0,120,?,NULL,NULL,NULL,1,'it','it',?,?)",
                alertId, configId, versionV1, 2, "{}", "REVIEWER", null,
                Timestamp.from(Instant.now().plusSeconds(7200)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
    }

    @Test
    @Transactional
    void invalidWorkOrderStatusIsRejectedByCheckConstraint() {
        long configId = insertConfig("M2_IT_CHECK");
        long versionId = insertVersion(configId, 1);
        long alertId = firstAlertId();

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO work_order(alert_id,config_id,config_version_id,config_version_no," +
                        "config_snapshot_json,assign_role,assignee_id,status,requires_review,sla_minutes," +
                        "due_at,resolution_review_id,resolved_at,cancelled_at,version,created_by,updated_by," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'BOGUS',0,120,?,NULL,NULL,NULL,1,'it','it',?,?)",
                alertId, configId, versionId, 1, "{}", "REVIEWER", null,
                Timestamp.from(Instant.now().plusSeconds(7200)),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
    }

    @Test
    @Transactional
    void crossAlertResolutionReviewIsRejectedByDatabase() {
        List<Long> alerts = jdbc.queryForList(
                "SELECT alert_id FROM ads_risk_alert ORDER BY alert_id LIMIT 2", Long.class);
        assertTrue(alerts.size() >= 2, "MySQL integration requires at least two seeded alerts");
        long configId = insertConfig("M2_IT_REVIEW");
        long versionId = insertVersion(configId, 1);
        long otherAlertId = alerts.get(1);
        long reviewId = insertReview(otherAlertId);
        long alertId = alerts.get(0);

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO work_order(alert_id,config_id,config_version_id,config_version_no," +
                        "config_snapshot_json,assign_role,assignee_id,status,requires_review,sla_minutes," +
                        "due_at,resolution_review_id,resolved_at,cancelled_at,version,created_by,updated_by," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,'RESOLVED',1,120,?,?,?,NULL,1,'it','it',?,?)",
                alertId, configId, versionId, 1, "{}", "REVIEWER", null,
                Timestamp.from(Instant.now().plusSeconds(7200)), reviewId,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private int constraintCount(String table, String name) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?",
                Integer.class, table, name);
        return value == null ? 0 : value;
    }

    private long firstAlertId() {
        Long id = jdbc.queryForObject("SELECT MIN(alert_id) FROM ads_risk_alert", Long.class);
        assertTrue(id != null, "MySQL integration requires a seeded alert");
        return id;
    }

    private long insertConfig(String code) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config(code,name,trigger_json,risk_score_threshold," +
                            "sla_minutes,requires_review,assign_role,status,version,published_version," +
                            "created_by,updated_by,created_at,updated_at) " +
                            "VALUES(?,?,CAST(? AS JSON),0.5,120,0,'REVIEWER','DRAFT',1,NULL,'it','it',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, "M2 MySQL integration");
            statement.setString(3, "{\"sourceSystems\":[],\"riskLevels\":[],\"alertStatuses\":[],\"reasonCodes\":[]}");
            statement.setTimestamp(4, now);
            statement.setTimestamp(5, now);
            return statement;
        }, keys);
        Number key = keys.getKey();
        assertTrue(key != null, "config id was not generated");
        return key.longValue();
    }

    private long insertVersion(long configId, int versionNo) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config_version(config_id,version_no,source_version,code,name," +
                            "trigger_json,risk_score_threshold,sla_minutes,requires_review,assign_role," +
                            "published_by,published_at,created_at) VALUES(?,?,1,?,?,CAST(? AS JSON),0.5,120,0," +
                            "'REVIEWER','it',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, configId);
            statement.setInt(2, versionNo);
            statement.setString(3, "M2_IT_" + configId + "_" + versionNo);
            statement.setString(4, "M2 MySQL integration");
            statement.setString(5, "{\"sourceSystems\":[],\"riskLevels\":[],\"alertStatuses\":[],\"reasonCodes\":[]}");
            statement.setTimestamp(6, now);
            statement.setTimestamp(7, now);
            return statement;
        }, keys);
        Number key = keys.getKey();
        assertTrue(key != null, "version id was not generated");
        return key.longValue();
    }

    private long insertReview(long alertId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ads_alert_review(alert_id,reviewer_id,review_result,comment," +
                            "previous_status,new_status,created_at) VALUES(?,'it','CONFIRMED','it'," +
                            "'OPEN','RESOLVED',CURRENT_TIMESTAMP(6))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, alertId);
            return statement;
        }, keys);
        Number key = keys.getKey();
        assertTrue(key != null, "review id was not generated");
        return key.longValue();
    }
}
