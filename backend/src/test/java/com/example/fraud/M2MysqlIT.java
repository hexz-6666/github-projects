package com.example.fraud;

import com.example.fraud.api.ApiException;
import com.example.fraud.config.RequestIdFilter;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderStatus;
import com.example.fraud.service.WorkOrderService;
import com.example.fraud.store.DuplicateWorkOrderException;
import com.example.fraud.store.FraudStore;
import com.example.fraud.store.WorkOrderStore;
import java.io.File;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gated MySQL integration checks. They never run in the normal Stub build.
 *
 * <p>Run only against a disposable/local MySQL 8.0.16+ database that already
 * has V1+V2+V3 applied. This class does not use the two-alert V2 seed; every
 * data-dependent test creates its own order/feature/score/alert fixture in a
 * transaction. Set all three variables or the entire class is skipped:</p>
 *
 * <pre>
 * $env:DB_URL='jdbc:mysql://127.0.0.1:3306/fraud_demo?...'
 * $env:DB_USERNAME='...'; $env:DB_PASSWORD='...'
 * mvn -q "-Dtest=M2MysqlIT" test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@SpringBootTest
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M2MysqlIT {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FraudStore fraudStore;

    @Autowired
    private WorkOrderStore workOrderStore;

    @Autowired
    private WorkOrderService workOrderService;

    @BeforeAll
    void applyV4BeforeDataFixtures() {
        // The data tests must fail on schema drift, not because V4 was never
        // applied. This also makes the test class self-contained for a disposable
        // database that already has V1+V2+V3.
        runV4();
    }

    @Test
    void v4CanBeExecutedTwiceAndDefinitionsAreExact() {
        runV4();
        runV4();

        assertUniqueIndex("ads_alert_review", "uk_review_id_alert", "review_id", "alert_id");
        assertUniqueIndex("work_order_config_version", "uk_wocv_id_config_version",
                "id", "config_id", "version_no");
        assertForeignKey("work_order_config", "fk_woc_published_version",
                "work_order_config_version", "id", "config_id", "published_version", "version_no");
        assertForeignKey("work_order", "fk_wo_config_version_consistent",
                "work_order_config_version", "config_version_id", "id",
                "config_id", "config_id", "config_version_no", "version_no");
        assertForeignKey("work_order", "fk_wo_resolution_review_alert",
                "ads_alert_review", "resolution_review_id", "review_id",
                "alert_id", "alert_id");
        assertCheck("work_order", "ck_wo_status");
        assertCheck("work_order", "ck_wo_open_unassigned");
        assertCheck("work_order_config_version", "ck_wocv_threshold");
        assertCheck("ads_risk_alert", "ck_alert_status");
    }

    @Test
    @Transactional
    void mismatchedConfigVersionTripletIsRejectedByDatabase() {
        Fixture fixture = fixture(1);
        long otherVersion = insertVersion(fixture.configId, 2);

        assertThrows(DataAccessException.class, () -> insertWorkOrder(
                fixture, fixture.alertIds.get(0), fixture.configId, fixture.versionId,
                2, "OPEN", null, false));
        // Keep the reference used by the assertion explicit: version id and
        // version number must belong to the same immutable version row.
        assertTrue(otherVersion != fixture.versionId);
    }

    @Test
    @Transactional
    void invalidWorkOrderStatusIsRejectedByCheckConstraint() {
        Fixture fixture = fixture(1);
        assertThrows(DataAccessException.class, () -> insertWorkOrder(
                fixture, fixture.alertIds.get(0), fixture.configId, fixture.versionId,
                1, "BOGUS", null, false));
    }

    @Test
    @Transactional
    void crossAlertResolutionReviewIsRejectedByDatabase() {
        Fixture fixture = fixture(2);
        long reviewOnOtherAlert = insertReview(fixture.alertIds.get(1));
        assertThrows(DataAccessException.class, () -> insertWorkOrder(
                fixture, fixture.alertIds.get(0), fixture.configId, fixture.versionId,
                1, "RESOLVED", reviewOnOtherAlert, true));
    }

    @Test
    @Transactional
    void mysqlStoreTranslatesUniqueKeyToDuplicateWorkOrderException() {
        Fixture fixture = fixture(1);
        WorkOrderRecord first = workOrder(fixture, fixture.alertIds.get(0));
        workOrderStore.insert(first);
        assertThrows(DuplicateWorkOrderException.class,
                () -> workOrderStore.insert(workOrder(fixture, fixture.alertIds.get(0))));
    }

    @Test
    void concurrentCreateAndReviewIsSerializedByMysql() throws Exception {
        Fixture fixture = committedFixture();
        try {
            Authentication auth = reviewerAuth();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> createFuture = pool.submit(() -> {
                await(start);
                try {
                    workOrderService.create(fixture.alertIds.get(0), fixture.configId,
                            auth, request(fixture.requestId));
                    return 201;
                } catch (ApiException exception) {
                    return exception.getStatus().value();
                }
            });
            Future<Integer> reviewFuture = pool.submit(() -> {
                await(start);
                try {
                    fraudStore.review(fixture.alertIds.get(0), "it_reviewer",
                            ReviewResult.CONFIRMED, "concurrency", 1);
                    return 201;
                } catch (ApiException exception) {
                    return exception.getStatus().value();
                }
            });
            start.countDown();
            int createStatus = createFuture.get(15, TimeUnit.SECONDS);
            int reviewStatus = reviewFuture.get(15, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "concurrency pool must terminate");

            assertEquals(201, reviewStatus, "review should always serialize successfully");
            assertTrue(createStatus == 201 || createStatus == 409,
                    "create must be 201 or 409, was " + createStatus);

            int orders = count("SELECT COUNT(*) FROM work_order WHERE alert_id=?",
                    fixture.alertIds.get(0));
            int createdEvents = count("SELECT COUNT(*) FROM work_order_event e " +
                            "JOIN work_order w ON w.id=e.work_order_id " +
                            "WHERE w.alert_id=? AND e.event_type='CREATED'",
                    fixture.alertIds.get(0));
            if (createStatus == 201) {
                assertEquals(1, orders);
                assertEquals(1, createdEvents);
                long workOrderId = jdbc.queryForObject(
                        "SELECT id FROM work_order WHERE alert_id=?",
                        Long.class, fixture.alertIds.get(0));
                assertEquals(1, count("SELECT COUNT(*) FROM audit_event " +
                                "WHERE target_type='WORK_ORDER' AND action='WORK_ORDER_CREATED' " +
                                "AND target_id=? AND request_id=?",
                        String.valueOf(workOrderId), fixture.requestId));
            } else {
                assertEquals(0, orders);
                assertEquals(0, createdEvents);
            }
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void concurrentCreatesForSameAlertAreIdempotentUnderMysql() throws Exception {
        Fixture fixture = committedFixture();
        try {
            Authentication auth = reviewerAuth();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = pool.submit(() -> {
                await(start);
                return createStatus(fixture, auth);
            });
            Future<Integer> second = pool.submit(() -> {
                await(start);
                return createStatus(fixture, auth);
            });
            start.countDown();
            int firstStatus = first.get(15, TimeUnit.SECONDS);
            int secondStatus = second.get(15, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "concurrency pool must terminate");

            assertTrue((firstStatus == 201 && secondStatus == 200)
                            || (firstStatus == 200 && secondStatus == 201),
                    "expected one 201 and one 200, got " + firstStatus + "/" + secondStatus);
            assertEquals(1, count("SELECT COUNT(*) FROM work_order WHERE alert_id=?",
                    fixture.alertIds.get(0)));
            assertEquals(1, count("SELECT COUNT(*) FROM work_order_event e " +
                            "JOIN work_order w ON w.id=e.work_order_id " +
                            "WHERE w.alert_id=? AND e.event_type='CREATED'",
                    fixture.alertIds.get(0)));
            long workOrderId = jdbc.queryForObject("SELECT id FROM work_order WHERE alert_id=?",
                    Long.class, fixture.alertIds.get(0));
            assertEquals(1, count("SELECT COUNT(*) FROM audit_event " +
                            "WHERE target_type='WORK_ORDER' AND action='WORK_ORDER_CREATED' " +
                            "AND target_id=? AND request_id=?",
                    String.valueOf(workOrderId), fixture.requestId));
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void failedEventWriteRollsBackWorkOrderAndLeavesNoAudit() {
        Fixture fixture = committedFixture();
        try {
            // The event and audit request_id columns are VARCHAR(64). An
            // overlong request id makes the event insert fail after the
            // work_order insert, without changing production code or using a
            // mock. The surrounding writer transaction must roll everything
            // back, including the preceding work_order row.
            fixture.requestId = String.join("", Collections.nCopies(65, "x"));
            Authentication auth = reviewerAuth();

            assertThrows(DataAccessException.class, () -> workOrderService.create(
                    fixture.alertIds.get(0), fixture.configId, auth, request(fixture.requestId)));

            assertEquals(0, count("SELECT COUNT(*) FROM work_order WHERE alert_id=?",
                    fixture.alertIds.get(0)));
            assertEquals(0, count("SELECT COUNT(*) FROM work_order_event e " +
                            "JOIN work_order w ON w.id=e.work_order_id WHERE w.alert_id=?",
                    fixture.alertIds.get(0)));
            assertEquals(0, count("SELECT COUNT(*) FROM audit_event " +
                            "WHERE target_type='WORK_ORDER' AND action='WORK_ORDER_CREATED' " +
                            "AND request_id=?",
                    fixture.requestId));
        } finally {
            cleanup(fixture);
        }
    }

    private int createStatus(Fixture fixture, Authentication auth) {
        try {
            WorkOrderService.CreateResult result = workOrderService.create(
                    fixture.alertIds.get(0), fixture.configId, auth, request(fixture.requestId));
            return result.isIdempotent() ? 200 : 201;
        } catch (ApiException exception) {
            return exception.getStatus().value();
        }
    }

    private void runV4() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new FileSystemResource(v4Script()));
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private File v4Script() {
        // Surefire normally exposes basedir; user.dir covers direct IDE/CLI
        // launches. Check both the base and its parent so both of these work:
        //   backend> mvn -q "-Dtest=M2MysqlIT" test
        //   repo-root> mvn -f backend/pom.xml -q "-Dtest=M2MysqlIT" test
        String[] bases = {System.getProperty("basedir"), System.getProperty("user.dir")};
        for (String base : bases) {
            if (base == null || base.trim().isEmpty()) {
                continue;
            }
            File directory = new File(base);
            for (int depth = 0; depth < 2 && directory != null; depth++) {
                File candidate = new File(directory,
                        "database/migrations/V4__v3_work_orders.sql");
                if (candidate.isFile()) {
                    return candidate;
                }
                directory = directory.getParentFile();
            }
        }
        throw new IllegalStateException("Cannot locate database/migrations/V4__v3_work_orders.sql "
                + "from basedir=" + System.getProperty("basedir")
                + " user.dir=" + System.getProperty("user.dir"));
    }

    private void assertUniqueIndex(String table, String index, String... expectedColumns) {
        Integer nonUnique = jdbc.queryForObject(
                "SELECT NON_UNIQUE FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? " +
                        "ORDER BY SEQ_IN_INDEX LIMIT 1",
                Integer.class, table, index);
        assertEquals(Integer.valueOf(0), nonUnique);
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? " +
                        "ORDER BY SEQ_IN_INDEX",
                String.class, table, index);
        assertEquals(Arrays.asList(expectedColumns), columns);
    }

    private void assertForeignKey(String table, String constraint, String referencedTable,
                                  String... columnPairs) {
        String type = jdbc.queryForObject(
                "SELECT CONSTRAINT_TYPE FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=?",
                String.class, table, constraint);
        assertEquals("FOREIGN KEY", type);
        String actualReferencedTable = jdbc.queryForObject(
                "SELECT REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? " +
                        "ORDER BY ORDINAL_POSITION LIMIT 1",
                String.class, table, constraint);
        assertEquals(referencedTable, actualReferencedTable);

        List<Map<String, Object>> actual = jdbc.queryForList(
                "SELECT COLUMN_NAME, REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? " +
                        "ORDER BY ORDINAL_POSITION",
                table, constraint);
        assertEquals(columnPairs.length / 2, actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(columnPairs[i * 2], actual.get(i).get("COLUMN_NAME"));
            assertEquals(columnPairs[i * 2 + 1], actual.get(i).get("REFERENCED_COLUMN_NAME"));
        }
    }

    private void assertCheck(String table, String constraint) {
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? " +
                        "AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE='CHECK'",
                table, constraint));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Fixture fixture(int alertCount) {
        Fixture fixture = new Fixture();
        fixture.requestId = "IT-REQ-" + suffix();
        String batch = "IT-BATCH-" + suffix();
        for (int i = 0; i < alertCount; i++) {
            String orderId = "IT-ORDER-" + suffix();
            fixture.orderIds.add(orderId);
            insertOrder(orderId, batch);
            long featureId = insertFeature(orderId, batch);
            long scoreId = insertScore(orderId, featureId);
            long alertId = insertAlert(orderId, scoreId);
            fixture.alertIds.add(alertId);
        }
        fixture.configId = insertConfig();
        fixture.versionId = insertVersion(fixture.configId, 1);
        jdbc.update("UPDATE work_order_config SET status='ACTIVE',published_version=1,updated_at=? WHERE id=?",
                Timestamp.from(Instant.now()), fixture.configId);
        return fixture;
    }

    private Fixture committedFixture() {
        return new TransactionTemplate(transactionManager).execute(status -> fixture(1));
    }

    private void cleanup(Fixture fixture) {
        if (fixture == null) {
            return;
        }
        // The request id is unique to this fixture, so this removes only audit
        // rows produced by this IT run even if a failed transaction left an
        // orphan audit row without a corresponding work_order row.
        jdbc.update("DELETE FROM audit_event WHERE target_type='WORK_ORDER' " +
                        "AND action='WORK_ORDER_CREATED' AND request_id=?",
                fixture.requestId);
        for (Long alertId : fixture.alertIds) {
            jdbc.update("DELETE FROM work_order_event WHERE work_order_id IN " +
                    "(SELECT id FROM work_order WHERE alert_id=?)", alertId);
            jdbc.update("DELETE FROM work_order WHERE alert_id=?", alertId);
            jdbc.update("DELETE FROM ads_alert_review WHERE alert_id=?", alertId);
            jdbc.update("DELETE FROM ads_risk_alert WHERE alert_id=?", alertId);
        }
        if (fixture.configId != null) {
            // work_order_config.published_version and
            // work_order_config_version.config_id are mutually referencing;
            // clear the pointer before removing the immutable versions.
            jdbc.update("UPDATE work_order_config SET published_version=NULL WHERE id=?",
                    fixture.configId);
            jdbc.update("DELETE FROM work_order_config_version WHERE config_id=?",
                    fixture.configId);
            jdbc.update("DELETE FROM work_order_config WHERE id=?", fixture.configId);
        }
        for (String orderId : fixture.orderIds) {
            jdbc.update("DELETE FROM ads_risk_score WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM dws_order_risk_feature WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM dwd_order WHERE order_id=?", orderId);
        }
    }

    private void insertOrder(String orderId, String batch) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO dwd_order(order_id,source_system,source_record_id,ingest_batch_id," +
                        "order_no,customer_ref,customer_email_masked,status,amount,currency,payment_count," +
                        "account_created_at,ordered_at,transaction_count_24h,data_origin,created_at,updated_at) " +
                        "VALUES(?, 'SYNTHETIC', ?, ?, ?, ?, NULL, 'PAID', 100.00, 'CNY', 1, ?, ?, 1, " +
                        "'SYNTHETIC', ?, ?)",
                orderId, orderId, batch, orderId, orderId,
                Timestamp.from(now.minusSeconds(86400)), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
    }

    private long insertFeature(String orderId, String batch) {
        KeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dws_order_risk_feature(order_id,feature_version,amount,currency," +
                            "payment_count,account_age_days,transaction_count_24h,as_of_at," +
                            "source_batch_id,created_at) VALUES(?, 'it-feature-v1',100.00,'CNY',1,1,1,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, orderId);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, batch);
            statement.setTimestamp(4, Timestamp.from(now));
            return statement;
        }, keys);
        return key(keys, "feature id");
    }

    private long insertScore(String orderId, long featureId) {
        KeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ads_risk_score(order_id,feature_id,score,risk_level,model_version," +
                            "model_type,feature_version,threshold,rule_codes,scored_at,created_at) " +
                            "VALUES(?,?,0.9000,'HIGH','it-model-v1','DEMO_RULE','it-feature-v1',0.5000," +
                            "CAST(? AS JSON),?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, orderId);
            statement.setLong(2, featureId);
            statement.setString(3, "[]");
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            return statement;
        }, keys);
        return key(keys, "score id");
    }

    private long insertAlert(String orderId, long scoreId) {
        KeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ads_risk_alert(order_id,score_id,alert_status,risk_level," +
                            "reason_codes,version,created_at,updated_at) " +
                            "VALUES(?,?,'OPEN','HIGH',CAST(? AS JSON),1,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, orderId);
            statement.setLong(2, scoreId);
            statement.setString(3, "[\"IT_REASON\"]");
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            return statement;
        }, keys);
        return key(keys, "alert id");
    }

    private long insertConfig() {
        KeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        String code = "M2_IT_" + suffix().toUpperCase();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config(code,name,description,priority,trigger_json," +
                            "risk_score_threshold,sla_minutes,requires_review,assign_role,status,version," +
                            "published_version,created_by,updated_by,created_at,updated_at) " +
                            "VALUES(?, 'M2 MySQL IT', NULL, 3, CAST(? AS JSON), 0.5, 120, 0, " +
                            "'REVIEWER', 'DRAFT', 1, NULL, 'it', 'it', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, code);
            statement.setString(2, "{\"sourceSystems\":[],\"riskLevels\":[],\"alertStatuses\":[],\"reasonCodes\":[]}");
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            return statement;
        }, keys);
        return key(keys, "config id");
    }

    private long insertVersion(long configId, int versionNo) {
        KeyHolder keys = new GeneratedKeyHolder();
        Instant now = Instant.now();
        String code = "M2_IT_V_" + configId + "_" + versionNo;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO work_order_config_version(config_id,version_no,source_version,code,name," +
                            "trigger_json,risk_score_threshold,sla_minutes,requires_review,assign_role," +
                            "published_by,published_at,created_at) VALUES(?,?,1,?,'M2 MySQL IT',CAST(? AS JSON)," +
                            "0.5,120,0,'REVIEWER','it',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, configId);
            statement.setInt(2, versionNo);
            statement.setString(3, code);
            statement.setString(4, "{\"sourceSystems\":[],\"riskLevels\":[],\"alertStatuses\":[],\"reasonCodes\":[]}");
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
            return statement;
        }, keys);
        return key(keys, "version id");
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
        return key(keys, "review id");
    }

    private void insertWorkOrder(Fixture fixture, long alertId, long configId, long versionId,
                                 int versionNo, String status, Long reviewId, boolean requiresReview) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO work_order(alert_id,config_id,config_version_id,config_version_no," +
                        "config_snapshot_json,assign_role,assignee_id,status,requires_review,sla_minutes," +
                        "due_at,resolution_review_id,resolved_at,cancelled_at,version,created_by,updated_by," +
                        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,120,?,?,?,NULL,1,'it','it',?,?)",
                alertId, configId, versionId, versionNo, "{}", "REVIEWER", null, status,
                requiresReview, Timestamp.from(now.plusSeconds(7200)),
                reviewId, reviewId == null ? null : Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
    }

    private WorkOrderRecord workOrder(Fixture fixture, long alertId) {
        Instant now = Instant.now();
        return new WorkOrderRecord(0L, alertId, fixture.configId, fixture.versionId, 1, "{}",
                "REVIEWER", null, WorkOrderStatus.OPEN, false, 120, now.plusSeconds(7200),
                null, null, null, 1, "it", "it", now, now);
    }

    private Authentication reviewerAuth() {
        return new UsernamePasswordAuthenticationToken("it_reviewer", "n/a",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_REVIEWER")));
    }

    private HttpServletRequest request(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, requestId);
        return request;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "concurrency start timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private long key(KeyHolder keys, String label) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException(label + " was not generated");
        }
        return key.longValue();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static class Fixture {
        private final List<Long> alertIds = new ArrayList<Long>();
        private final List<String> orderIds = new ArrayList<String>();
        private String requestId;
        private Long configId;
        private Long versionId;
    }
}
