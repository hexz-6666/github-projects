package com.example.fraud;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Timestamp;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith({M4DashboardMysqlIT.DockerGate.class, M4DashboardMysqlIT.ApiVersionCleanup.class})
@Testcontainers(disabledWithoutDocker = false)
@SpringBootTest(properties = {
        "app.ops-log.enabled=false",
        "app.ops-log.prune-initial-delay-ms=86400000"
})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M4DashboardMysqlIT {
    private static String managedPreviousApiVersion;
    private static boolean apiVersionManaged;
    private static BooleanSupplier dockerProbeOverride;

    private static final String MYSQL_IMAGE =
            "mysql:8.0.36@sha256:a532724022429812ec797c285c1b540a644c15e248579c6bfdf12a8fbaab4964";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse(MYSQL_IMAGE).asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("fraud_demo")
            .withUsername("m4_test")
            .withPassword("m4_test_only")
            .withCommand("--default-time-zone=+00:00")
            .withStartupTimeoutSeconds(180);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        // Spring resolves dynamic properties while creating the context, before the
        // Testcontainers beforeAll callback; start here so getJdbcUrl() is valid.
        // The @Container extension sees the running container and remains the owner
        // of its stop/cleanup lifecycle.
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        registry.add("DB_URL", MYSQL::getJdbcUrl);
        registry.add("DB_USERNAME", MYSQL::getUsername);
        registry.add("DB_PASSWORD", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private boolean schemaInitialized;
    private boolean schemaReady;

    @BeforeAll
    void setUp() throws Exception {
        try {
            assertContainerIdentity();
            M4MysqlFixture.applySchema(jdbc);
            schemaInitialized = true;
            M4MysqlFixture.assertSchema(jdbc);
            M4MysqlFixture.insert(jdbc);
            schemaReady = true;
        } catch (Throwable failure) {
            // Best effort even when DDL or fixture insertion fails midway.
            cleanupQuietly();
            throw failure;
        }
    }

    @AfterAll
    void tearDown() {
        if (schemaReady) {
            M4MysqlFixture.cleanup(jdbc);
            M4MysqlFixture.assertClean(jdbc);
        } else if (schemaInitialized) {
            cleanupQuietly();
        }
    }

    private void cleanupQuietly() {
        try {
            M4MysqlFixture.cleanup(jdbc);
        } catch (RuntimeException cleanupFailure) {
            cleanupFailure.printStackTrace();
        }
    }

    @Test
    void containerIdentityAndUtcSessionAreReal() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String url = meta.getURL();
            assertTrue(url.contains(":" + MYSQL.getMappedPort(3306) + "/fraud_demo"),
                    "JDBC URL does not use the Testcontainers mapped port/database: " + url);
            assertTrue(url.contains(MYSQL.getHost()),
                    "JDBC URL does not use the Testcontainers host: " + url);
            assertEquals("fraud_demo", jdbc.queryForObject("SELECT DATABASE()", String.class));
        }
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals("2026-01-05", jdbc.queryForObject(
                "SELECT DATE_FORMAT(CAST('2026-01-05 23:59:59.999' AS DATETIME(6)),'%Y-%m-%d')",
                String.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT WEEKDAY(CAST('2026-01-05' AS DATE))", Integer.class));

        Timestamp orderedAt = jdbc.queryForObject(
                "SELECT ordered_at FROM dwd_order WHERE order_id=?",
                Timestamp.class, M4MysqlFixture.PREFIX + "-O-OLIST-MID");
        assertNotNull(orderedAt);
        assertEquals(M4MysqlFixture.BASE, orderedAt.toInstant());
    }

    @Test
    void dashboardUsesUtcBucketsRangeEndAndOrderOrigins() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.total").value(M4MysqlFixture.FIXTURE_ORDERS_IN_RANGE))
                .andExpect(jsonPath("$.orders.series.length()").value(2))
                .andExpect(jsonPath("$.orders.series[0].origin").value("OLIST"))
                .andExpect(jsonPath("$.orders.series[0].total").value(2))
                .andExpect(jsonPath("$.orders.series[1].origin").value("SYNTHETIC"))
                .andExpect(jsonPath("$.orders.series[1].total").value(1))
                .andExpect(jsonPath("$.alerts.total").value(M4MysqlFixture.FIXTURE_ALERTS))
                .andExpect(jsonPath("$.workOrders.total").value(M4MysqlFixture.FIXTURE_WORK_ORDERS))
                .andExpect(jsonPath("$.reviews.total").value(M4MysqlFixture.FIXTURE_REVIEWS_RESOLVED))
                .andExpect(jsonPath("$.reviews.byResult[2].key").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.reviews.byResult[2].count").value(2))
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].start").value("2026-01-05"))
                .andExpect(jsonPath("$.buckets[0].partial").value(false))
                .andExpect(jsonPath("$.snapshot.alertsHasData").value(true))
                .andExpect(jsonPath("$.snapshot.workOrdersHasData").value(true))
                .andExpect(jsonPath("$.snapshot.asOfKind").value("REQUEST_GENERATION_TIME"));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-12T00:00:00Z")
                        .param("bucket", "week")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].start").value("2026-01-05"))
                .andExpect(jsonPath("$.buckets[0].partial").value(false));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T12:00:00Z")
                        .param("to", "2026-01-06T12:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].partial").value(true))
                .andExpect(jsonPath("$.buckets[1].partial").value(true));

        // The current snapshot must remain populated even when the trend window is empty.
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-01-02T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.total").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.snapshot.alertsHasData").value(true))
                .andExpect(jsonPath("$.snapshot.workOrdersHasData").value(true))
                .andExpect(jsonPath("$.snapshot.asOfKind").value("REQUEST_GENERATION_TIME"));
    }

    @Test
    void dashboardDqAndImportsUseTerminalFixtureRules() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imports.batches.length()")
                        .value(M4MysqlFixture.RETURNED_IMPORTS))
                .andExpect(jsonPath("$.imports.batches[0].batchId")
                        .value(M4MysqlFixture.PREFIX + "-B-20"))
                .andExpect(jsonPath("$.imports.batches[1].batchId")
                        .value(M4MysqlFixture.PREFIX + "-B-19"))
                .andExpect(jsonPath("$.imports.batches[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.dq.scope").value("ALL_ENDED_BATCHES"))
                .andExpect(jsonPath("$.dq.totalChecks").value(M4MysqlFixture.FIXTURE_DQ_TOTAL))
                .andExpect(jsonPath("$.dq.evaluatedChecks").value(M4MysqlFixture.FIXTURE_DQ_EVALUATED))
                .andExpect(jsonPath("$.dq.infoChecks").value(1))
                .andExpect(jsonPath("$.dq.infoFailed").value(1))
                .andExpect(jsonPath("$.dq.failedWarn").value(1))
                .andExpect(jsonPath("$.dq.failedError").value(0))
                .andExpect(jsonPath("$.dq.passRate").value(M4MysqlFixture.FIXTURE_DQ_PASS_RATE))
                .andExpect(jsonPath("$.dq.bySource.length()").value(2))
                .andExpect(jsonPath("$.dq.bySource[0].sourceSystem").value("IEEE_CIS"))
                .andExpect(jsonPath("$.dq.bySource[0].passRate")
                        .value(M4MysqlFixture.FIXTURE_IEEE_PASS_RATE))
                .andExpect(jsonPath("$.dq.bySource[1].sourceSystem").value("OLIST"))
                .andExpect(jsonPath("$.dq.bySource[1].passRate")
                        .value(M4MysqlFixture.FIXTURE_OLIST_PASS_RATE));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-02-01T00:00:00Z")
                        .param("to", "2026-02-02T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dq.hasData").value(true))
                .andExpect(jsonPath("$.dq.scope").value("ALL_ENDED_BATCHES"))
                .andExpect(jsonPath("$.dq.totalChecks").value(1))
                .andExpect(jsonPath("$.dq.evaluatedChecks").value(0))
                .andExpect(jsonPath("$.dq.infoChecks").value(1))
                .andExpect(jsonPath("$.dq.passRate").value(nullValue()))
                .andExpect(jsonPath("$.dq.bySource.length()").value(1))
                .andExpect(jsonPath("$.dq.bySource[0].sourceSystem").value("INFO_ONLY"))
                .andExpect(jsonPath("$.dq.bySource[0].passRate").value(nullValue()));
    }

    @Test
    void dashboardAuthorizationAndResolvedReviewSemanticsAreReal() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.total").value(1));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-06T00:00:00Z")
                        .with(user("demo_analyst").roles("ANALYST")))
                .andExpect(status().isForbidden());
    }

    static class DockerGate implements ExecutionCondition {
        private final BooleanSupplier dockerProbe;

        DockerGate() {
            this(M4DashboardMysqlIT::probeDocker);
        }

        DockerGate(BooleanSupplier dockerProbe) {
            this.dockerProbe = dockerProbe;
        }

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            boolean required = Boolean.parseBoolean(System.getProperty("m4.mysql.required", "false"));
            // Capture once per class lifecycle; JUnit may evaluate class and method containers.
            boolean alreadyCaptured = apiVersionManaged;
            String previous = alreadyCaptured
                    ? managedPreviousApiVersion
                    : M4ApiVersionLifecycle.begin();
            boolean available = false;
            if (!required) {
                try {
                    // Testcontainers-native preflight; no PATH-based docker command.
                    available = dockerProbe.getAsBoolean();
                } catch (Throwable failure) {
                    // Do not leak api.version even for an Error/non-RuntimeException path.
                    M4ApiVersionLifecycle.restore(previous);
                    apiVersionManaged = false;
                    managedPreviousApiVersion = null;
                    if (failure instanceof Error) {
                        throw (Error) failure;
                    }
                    return ConditionEvaluationResult.disabled(
                            "Docker probe failed; set -Dm4.mysql.required=true to fail instead");
                }
            }
            M4ApiVersionLifecycle.Outcome outcome =
                    M4ApiVersionLifecycle.decide(previous, required, available);
            if (!outcome.isEnabled()) {
                if (alreadyCaptured) {
                    M4ApiVersionLifecycle.restore(previous);
                }
                apiVersionManaged = false;
                managedPreviousApiVersion = null;
                return ConditionEvaluationResult.disabled(
                        "Docker unavailable; set -Dm4.mysql.required=true to fail instead");
            }
            if (!alreadyCaptured) {
                managedPreviousApiVersion = outcome.getPrevious();
                apiVersionManaged = true;
            }
            return ConditionEvaluationResult.enabled(
                    required ? "required MySQL mode" : "Docker available");
        }
    }

    private static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    private static boolean probeDocker() {
        BooleanSupplier override = dockerProbeOverride;
        return override == null ? isDockerAvailable() : override.getAsBoolean();
    }

    static void setDockerProbeForTest(BooleanSupplier probe) {
        dockerProbeOverride = probe;
    }

    static void resetApiVersionLifecycleForTest() {
        dockerProbeOverride = null;
        if (apiVersionManaged) {
            M4ApiVersionLifecycle.restore(managedPreviousApiVersion);
            managedPreviousApiVersion = null;
            apiVersionManaged = false;
        }
    }

    static class ApiVersionCleanup implements AfterAllCallback {
        @Override
        public void afterAll(ExtensionContext context) {
            if (apiVersionManaged) {
                M4ApiVersionLifecycle.restore(managedPreviousApiVersion);
                managedPreviousApiVersion = null;
                apiVersionManaged = false;
            }
        }
    }

    private void assertContainerIdentity() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertEquals(MYSQL.getJdbcUrl(), url,
                    "DataSource URL is not the Testcontainers JDBC URL: " + url);
            assertTrue(url.contains("//" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                            + "/fraud_demo"),
                    "DataSource URL does not use the container host/port/database: " + url);
            assertEquals("fraud_demo",
                    jdbc.queryForObject("SELECT DATABASE()", String.class));
            assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        }
    }
}
