package com.example.fraud;

import com.example.fraud.api.DashboardResponse;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.ReviewRecord;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.service.DashboardService;
import com.example.fraud.store.DashboardStore;
import com.example.fraud.store.StubDashboardStore;
import com.example.fraud.store.StubFraudStore;
import com.example.fraud.store.StubV2Store;
import com.example.fraud.store.StubWorkOrderStore;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class M4DashboardServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void onlyAlertsMakesAlertSnapshotVisibleAndWorkOrderSnapshotEmpty() {
        DashboardStore store = new SnapshotStore(Collections.singletonMap("HIGH", 1L),
                Collections.emptyMap());
        DashboardResponse response = new DashboardService(store, CLOCK)
                .overview("2026-11-01T00:00:00Z", "2026-11-10T00:00:00Z", "day");
        Map<String, Object> snapshot = response.getSnapshot();
        assertEquals(Boolean.TRUE, snapshot.get("alertsHasData"));
        assertEquals(Boolean.FALSE, snapshot.get("workOrdersHasData"));
        assertEquals(Boolean.TRUE, snapshot.get("hasData"));
    }

    @Test
    void onlyWorkOrdersMakesWorkOrderSnapshotVisibleAndAlertSnapshotEmpty() {
        DashboardStore store = new SnapshotStore(Collections.emptyMap(),
                Collections.singletonMap("OPEN", 1L));
        DashboardResponse response = new DashboardService(store, CLOCK)
                .overview("2026-11-01T00:00:00Z", "2026-11-10T00:00:00Z", "day");
        Map<String, Object> snapshot = response.getSnapshot();
        assertEquals(Boolean.FALSE, snapshot.get("alertsHasData"));
        assertEquals(Boolean.TRUE, snapshot.get("workOrdersHasData"));
        assertEquals(Boolean.TRUE, snapshot.get("hasData"));
    }

    @Test
    void stubCountsResolvedNewStatusEvenWhenReviewResultIsInconclusive() throws Exception {
        StubFraudStore fraudStore = new StubFraudStore(CLOCK);
        Field field = StubFraudStore.class.getDeclaredField("reviews");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ReviewRecord> reviews = (List<ReviewRecord>) field.get(fraudStore);
        reviews.add(new ReviewRecord(9001L, 1L, "fixture-reviewer",
                ReviewResult.INCONCLUSIVE, "synthetic fixture", AlertStatus.REVIEWING,
                AlertStatus.RESOLVED, Instant.parse("2026-11-06T00:00:00Z")));

        StubDashboardStore store = new StubDashboardStore(fraudStore,
                new StubWorkOrderStore(), new StubV2Store());
        List<DashboardStore.DailyCount> counts = store.reviewResolvedCounts(
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-10T00:00:00Z"));
        assertEquals(1, counts.size());
        assertEquals(1L, counts.get(0).getCount());
        assertEquals(1L, store.reviewResultCounts(
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-10T00:00:00Z")).get("INCONCLUSIVE"));
    }

    private static class SnapshotStore implements DashboardStore {
        private final Map<String, Long> alerts;
        private final Map<String, Long> workOrders;

        private SnapshotStore(Map<String, Long> alerts, Map<String, Long> workOrders) {
            this.alerts = new LinkedHashMap<String, Long>(alerts);
            this.workOrders = new LinkedHashMap<String, Long>(workOrders);
        }

        @Override
        public List<DailyCount> orderCounts(Instant from, Instant to) {
            return Collections.emptyList();
        }

        @Override
        public List<DailyCount> alertCounts(Instant from, Instant to) {
            return Collections.emptyList();
        }

        @Override
        public List<DailyCount> workOrderCreatedCounts(Instant from, Instant to) {
            return Collections.emptyList();
        }

        @Override
        public List<DailyCount> reviewResolvedCounts(Instant from, Instant to) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Long> reviewResultCounts(Instant from, Instant to) {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Long> alertRiskSnapshot() {
            return alerts;
        }

        @Override
        public Map<String, Long> workOrderStatusSnapshot() {
            return workOrders;
        }

        @Override
        public List<ImportBatch> terminalImports(Instant from, Instant to, int limit) {
            return Collections.emptyList();
        }

        @Override
        public List<DqCheck> dqChecks(Instant from, Instant to) {
            return Collections.emptyList();
        }
    }
}
