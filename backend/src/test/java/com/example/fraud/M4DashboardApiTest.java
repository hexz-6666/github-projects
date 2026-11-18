package com.example.fraud;

import com.example.fraud.store.V2Store;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class M4DashboardApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private V2Store v2Store;

    @Test
    void adminGetsZeroFilledBucketsAndTruthfulEmptySections() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-08T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.timezone").value("UTC"))
                .andExpect(jsonPath("$.buckets.length()").value(7))
                .andExpect(jsonPath("$.buckets[0].partial").value(false))
                .andExpect(jsonPath("$.orders.hasData").value(false))
                .andExpect(jsonPath("$.alerts.hasData").value(false))
                .andExpect(jsonPath("$.workOrders.hasData").value(false))
                .andExpect(jsonPath("$.reviews.hasData").value(false))
                .andExpect(jsonPath("$.alerts.points[0]").value(0))
                .andExpect(jsonPath("$.snapshot.hasData").value(true))
                .andExpect(jsonPath("$.snapshot.asOf").isNotEmpty())
                .andExpect(jsonPath("$.snapshot.asOfKind").value("REQUEST_GENERATION_TIME"));
    }

    @Test
    void adminSeesSeedOrdersByActualOriginAndSnapshotCounts() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-04T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.hasData").value(true))
                .andExpect(jsonPath("$.orders.total").value(3))
                .andExpect(jsonPath("$.orders.series.length()").value(1))
                .andExpect(jsonPath("$.orders.series[0].origin").value("SYNTHETIC"))
                .andExpect(jsonPath("$.orders.series[0].points[2]").value(3))
                .andExpect(jsonPath("$.alerts.total").value(2))
                .andExpect(jsonPath("$.reviews.byResult.length()").value(3))
                .andExpect(jsonPath("$.reviews.byResult[0].key").value("CONFIRMED"))
                .andExpect(jsonPath("$.snapshot.alertsByRiskLevel[0].key").value("LOW"))
                .andExpect(jsonPath("$.snapshot.alertsByRiskLevel[0].count").value(1))
                .andExpect(jsonPath("$.snapshot.alertsByRiskLevel[2].key").value("HIGH"))
                .andExpect(jsonPath("$.snapshot.alertsByRiskLevel[2].count").value(1));
    }

    @Test
    void defaultRangeIsBoundedTo366DaysAtWeekGranularity() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.maxDays").value(366))
                .andExpect(jsonPath("$.range.bucket").value("week"))
                .andExpect(jsonPath("$.range.timezone").value("UTC"));
    }

    @Test
    void boundaryBucketsAreMarkedPartial() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-02T12:00:00Z")
                        .param("to", "2026-01-05T12:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(4))
                .andExpect(jsonPath("$.buckets[0].partial").value(true))
                .andExpect(jsonPath("$.buckets[3].partial").value(true));
    }

    @Test
    void rangeAbove366DaysIsRejected() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2026-01-03T00:00:00Z")
                        .param("bucket", "week")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void dayBucketIsRejectedAboveNinetyDays() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-05-01T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void reviewerCannotReadAdminDashboard() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .with(user("demo_reviewer").roles("REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/dashboard/overview")
                        .with(user("demo_analyst").roles("ANALYST")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rangeEndIsExclusiveAtTheOrderBoundary() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-03T08:00:00Z")
                        .param("to", "2026-01-03T09:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.total").value(1));
    }

    @Test
    void isoWeekStartsOnMonday() throws Exception {
        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-01-05T00:00:00Z")
                        .param("to", "2026-01-12T00:00:00Z")
                        .param("bucket", "week")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].start").value("2026-01-05"))
                .andExpect(jsonPath("$.buckets[0].partial").value(false));
    }

    @Test
    void onlyInfoChecksMakePassRateNull() throws Exception {
        insertImport("M4-INFO-ONLY", "OLIST", "SUCCEEDED",
                "2026-11-03T00:00:00Z", 1, 0);
        v2Store.saveDqChecks(Arrays.asList(check("M4-INFO-ONLY", "INFO", false)));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dq.hasData").value(true))
                .andExpect(jsonPath("$.dq.evaluatedChecks").value(0))
                .andExpect(jsonPath("$.dq.infoChecks").value(1))
                .andExpect(jsonPath("$.dq.infoFailed").value(1))
                .andExpect(jsonPath("$.dq.passRate").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void terminalImportsAndDqExcludeRunningAndInfoFromPassRate() throws Exception {
        insertImport("M4-IMPORT-OK", "OLIST", "SUCCEEDED",
                "2026-11-03T00:00:00Z", 10, 2);
        insertImport("M4-IMPORT-FAIL", "IEEE_CIS", "FAILED",
                "2026-11-04T00:00:00Z", 0, 5);
        insertImport("M4-IMPORT-RUN", "OLIST", "RUNNING",
                "2026-11-05T00:00:00Z", 0, 0);
        v2Store.saveDqChecks(Arrays.asList(
                check("M4-IMPORT-OK", "ERROR", true),
                check("M4-IMPORT-OK", "WARN", false),
                check("M4-IMPORT-OK", "INFO", false)));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imports.hasData").value(true))
                .andExpect(jsonPath("$.imports.batches.length()").value(2))
                .andExpect(jsonPath("$.imports.batches[0].status").value("FAILED"))
                .andExpect(jsonPath("$.imports.batches[0].acceptedRows").value(0))
                .andExpect(jsonPath("$.imports.batches[1].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.dq.hasData").value(true))
                .andExpect(jsonPath("$.dq.totalChecks").value(3))
                .andExpect(jsonPath("$.dq.evaluatedChecks").value(2))
                .andExpect(jsonPath("$.dq.infoChecks").value(1))
                .andExpect(jsonPath("$.dq.infoFailed").value(1))
                .andExpect(jsonPath("$.dq.failedWarn").value(1))
                .andExpect(jsonPath("$.dq.passRate").value(0.5))
                .andExpect(jsonPath("$.dq.scope").value("ALL_ENDED_BATCHES"))
                .andExpect(jsonPath("$.dq.bySource.length()").value(1))
                .andExpect(jsonPath("$.dq.bySource[0].sourceSystem").value("OLIST"));
    }

    @Test
    void dqBySourceSeparatesOlistAndIeeeChecks() throws Exception {
        insertImport("M4-DQ-OLIST", "OLIST", "SUCCEEDED",
                "2026-11-03T00:00:00Z", 1, 0);
        insertImport("M4-DQ-IEEE", "IEEE_CIS", "SUCCEEDED",
                "2026-11-04T00:00:00Z", 1, 0);
        v2Store.saveDqChecks(Arrays.asList(
                check("M4-DQ-OLIST", "ERROR", true),
                check("M4-DQ-IEEE", "WARN", false)));

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dq.bySource.length()").value(2))
                .andExpect(jsonPath("$.dq.bySource[0].sourceSystem").value("IEEE_CIS"))
                .andExpect(jsonPath("$.dq.bySource[0].passRate").value(0.0))
                .andExpect(jsonPath("$.dq.bySource[1].sourceSystem").value("OLIST"))
                .andExpect(jsonPath("$.dq.bySource[1].passRate").value(1.0));
    }

    @Test
    void terminalImportsUseBatchIdDescendingForSameStartedAt() throws Exception {
        insertImport("M4-SAME-A", "OLIST", "SUCCEEDED",
                "2026-11-03T00:00:00Z", 1, 0);
        insertImport("M4-SAME-B", "OLIST", "FAILED",
                "2026-11-03T00:00:00Z", 0, 1);

        mvc.perform(get("/api/v3/dashboard/overview")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-10T00:00:00Z")
                        .param("bucket", "day")
                        .with(user("demo_admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imports.batches.length()").value(2))
                .andExpect(jsonPath("$.imports.batches[0].batchId").value("M4-SAME-B"))
                .andExpect(jsonPath("$.imports.batches[1].batchId").value("M4-SAME-A"));
    }

    private void insertImport(String batchId, String source, String status, String startedAt,
                              int accepted, int rejected) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("batchId", batchId);
        row.put("sourceSystem", source);
        row.put("status", status);
        row.put("startedAt", startedAt);
        row.put("acceptedRows", accepted);
        row.put("rejectedRows", rejected);
        v2Store.insertImport(row);
    }

    private Map<String, Object> check(String batchId, String severity, boolean passed) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("batchId", batchId);
        row.put("severity", severity);
        row.put("passed", passed);
        return row;
    }
}
