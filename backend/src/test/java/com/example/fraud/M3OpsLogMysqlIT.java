package com.example.fraud;

import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.service.OpsLogService;
import com.example.fraud.store.OpsLogStore;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M3OpsLogMysqlIT {
    private static final String PREFIX = "OPS-IT-";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OpsLogStore store;

    @Autowired
    private OpsLogService service;

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    void applyV5() {
        runV5();
        runV5();
        service.probeStorage();
    }

    @Test
    void v5IsAdditiveAndHasExpectedColumns() {
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_log_event'"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_log_event' " +
                "AND COLUMN_NAME='related_request_id'"));
        assertTrue(count("SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='ops_log_event' " +
                "AND INDEX_NAME='idx_ops_recent'") >= 1);
    }

    @Test
    void rejectedClientCanaryDoesNotReachDatabase() throws Exception {
        String canary = "CANARY_OPS_DO_NOT_STORE_" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"code\":\"CLIENT_HTTP_400\",\"route\":\"ORDERS\"," +
                "\"summary\":\"" + canary + "\",\"actorId\":\"" + canary + "\"," +
                "\"url\":\"https://example.invalid/" + canary + "\"}";

        mvc.perform(post("/api/v3/ops/client-errors").with(csrf())
                        .with(user("demo_admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        assertEquals(0, count("SELECT COUNT(*) FROM ops_log_event " +
                        "WHERE CONCAT_WS('|',route,code,summary,task_type,task_id," +
                        "request_id,related_request_id,actor_id,build_version) LIKE ?",
                "%" + canary + "%"));
    }

    @Test
    void queryOrderingFiltersAndCursorWork() {
        String taskId = PREFIX + "QUERY-" + System.nanoTime();
        try {
            append(taskId, Instant.now().minusSeconds(20), OpsLogLevel.INFO);
            append(taskId, Instant.now().minusSeconds(10), OpsLogLevel.WARN);
            append(taskId, Instant.now(), OpsLogLevel.ERROR);

            List<OpsLogEvent> first = store.query(new OpsLogStore.Query("TASK", "INFO",
                    null, taskId, null, null, null, null, null, null, 2));
            assertEquals(2, first.size());
            assertEquals(OpsLogLevel.ERROR, first.get(0).getLevel());
            assertEquals(OpsLogLevel.WARN, first.get(1).getLevel());

            OpsLogEvent cursor = first.get(1);
            List<OpsLogEvent> older = store.query(new OpsLogStore.Query("TASK", "INFO",
                    null, taskId, null, null, null, null, cursor.getOccurredAt(),
                    cursor.getEventId(), 2));
            assertEquals(1, older.size());
            assertEquals(OpsLogLevel.INFO, older.get(0).getLevel());
        } finally {
            cleanup(taskId);
        }
    }

    @Test
    void minLevelAndTimeFiltersWork() {
        String taskId = PREFIX + "FILTER-" + System.nanoTime();
        Instant base = Instant.now().minusSeconds(60);
        try {
            append(taskId, base.minusSeconds(20), OpsLogLevel.INFO);
            append(taskId, base.minusSeconds(10), OpsLogLevel.WARN);
            append(taskId, base, OpsLogLevel.ERROR);
            List<OpsLogEvent> warn = store.query(new OpsLogStore.Query("TASK", "WARN",
                    null, taskId, null, null, base.minusSeconds(15), base.plusSeconds(1),
                    null, null, 10));
            assertEquals(2, warn.size());
            List<OpsLogEvent> errors = store.query(new OpsLogStore.Query("TASK", "ERROR",
                    null, taskId, null, null, null, null, null, null, 10));
            assertEquals(1, errors.size());
            assertEquals(OpsLogLevel.ERROR, errors.get(0).getLevel());
        } finally {
            cleanup(taskId);
        }
    }

    @Test
    void retentionPrunesOnlyOldOpsEvents() {
        String taskId = PREFIX + "OLD-" + System.nanoTime();
        try {
            append(taskId, Instant.now().minusSeconds(31L * 24 * 3600), OpsLogLevel.INFO);
            service.prune();
            assertEquals(0, count("SELECT COUNT(*) FROM ops_log_event WHERE task_id=?", taskId));
        } finally {
            cleanup(taskId);
        }
    }

    private void append(String taskId, Instant at, OpsLogLevel level) {
        store.append(new OpsLogEvent(at, OpsLogSource.TASK, level, null,
                OpsLogCode.IMPORT_COMPLETED, "IMPORT_OLIST", taskId, null, null,
                "it", null, null));
    }

    private void cleanup(String taskId) {
        jdbc.update("DELETE FROM ops_log_event WHERE task_id=?", taskId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void runV5() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new FileSystemResource(v5Script()));
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private File v5Script() {
        String[] bases = {System.getProperty("basedir"), System.getProperty("user.dir")};
        for (String base : bases) {
            if (base == null || base.trim().isEmpty()) {
                continue;
            }
            File directory = new File(base);
            for (int depth = 0; depth < 2 && directory != null; depth++) {
                File candidate = new File(directory,
                        "database/migrations/V5__v3_ops_events.sql");
                if (candidate.isFile()) {
                    return candidate;
                }
                directory = directory.getParentFile();
            }
        }
        throw new IllegalStateException("Cannot locate V5 migration from basedir="
                + System.getProperty("basedir") + " user.dir=" + System.getProperty("user.dir"));
    }
}
