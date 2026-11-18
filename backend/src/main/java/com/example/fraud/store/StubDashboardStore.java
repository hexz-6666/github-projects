package com.example.fraud.store;

import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.ReviewRecord;
import com.example.fraud.domain.WorkOrderRecord;
import com.example.fraud.domain.WorkOrderScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class StubDashboardStore implements DashboardStore {
    private static final int ALL = Integer.MAX_VALUE;

    private final FraudStore fraudStore;
    private final WorkOrderStore workOrderStore;
    private final V2Store v2Store;

    public StubDashboardStore(FraudStore fraudStore, WorkOrderStore workOrderStore, V2Store v2Store) {
        this.fraudStore = fraudStore;
        this.workOrderStore = workOrderStore;
        this.v2Store = v2Store;
    }

    @Override
    public List<DailyCount> orderCounts(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (OrderRecord order : fraudStore.findOrders(null, null, 0, ALL)) {
            if (inRange(order.getOrderedAt(), from, to)) {
                String origin = order.getDataOrigin() == null ? "UNKNOWN" : order.getDataOrigin();
                add(counts, day(order.getOrderedAt()) + "|" + origin);
            }
        }
        return dailyCounts(counts, true);
    }

    @Override
    public List<DailyCount> alertCounts(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (AlertRecord alert : fraudStore.findAlerts(null, null, 0, ALL)) {
            if (inRange(alert.getCreatedAt(), from, to)) {
                add(counts, day(alert.getCreatedAt()) + "|");
            }
        }
        return dailyCounts(counts, false);
    }

    @Override
    public List<DailyCount> workOrderCreatedCounts(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        List<WorkOrderRecord> rows = workOrderStore.findWorkOrders(
                WorkOrderScope.ALL, null, "ADMIN", null, 0, ALL);
        for (WorkOrderRecord row : rows) {
            if (inRange(row.getCreatedAt(), from, to)) {
                add(counts, day(row.getCreatedAt()) + "|");
            }
        }
        return dailyCounts(counts, false);
    }

    @Override
    public List<DailyCount> reviewResolvedCounts(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (AlertRecord alert : fraudStore.findAlerts(null, null, 0, ALL)) {
            for (ReviewRecord review : fraudStore.findReviews(alert.getAlertId())) {
                if (review.getNewStatus() != null
                        && "RESOLVED".equals(review.getNewStatus().name())
                        && inRange(review.getCreatedAt(), from, to)) {
                    add(counts, day(review.getCreatedAt()) + "|");
                }
            }
        }
        return dailyCounts(counts, false);
    }

    @Override
    public Map<String, Long> reviewResultCounts(Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (AlertRecord alert : fraudStore.findAlerts(null, null, 0, ALL)) {
            for (ReviewRecord review : fraudStore.findReviews(alert.getAlertId())) {
                if (inRange(review.getCreatedAt(), from, to)) {
                    add(counts, review.getReviewResult().name());
                }
            }
        }
        return counts;
    }

    @Override
    public Map<String, Long> alertRiskSnapshot() {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (AlertRecord alert : fraudStore.findAlerts(null, null, 0, ALL)) {
            add(counts, alert.getRiskLevel().name());
        }
        return counts;
    }

    @Override
    public Map<String, Long> workOrderStatusSnapshot() {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        List<WorkOrderRecord> rows = workOrderStore.findWorkOrders(
                WorkOrderScope.ALL, null, "ADMIN", null, 0, ALL);
        for (WorkOrderRecord row : rows) {
            add(counts, row.getStatus().name());
        }
        return counts;
    }

    @Override
    public List<ImportBatch> terminalImports(Instant from, Instant to, int limit) {
        List<ImportBatch> result = new ArrayList<ImportBatch>();
        for (Map<String, Object> row : v2Store.findImports(0, ALL)) {
            String status = String.valueOf(row.get("status"));
            if (!terminal(status)) {
                continue;
            }
            Instant startedAt = instant(row.get("startedAt"));
            if (startedAt == null || !inRange(startedAt, from, to)) {
                continue;
            }
            result.add(new ImportBatch(String.valueOf(row.get("batchId")),
                    String.valueOf(row.get("sourceSystem")), status, startedAt,
                    integer(row.get("acceptedRows")), integer(row.get("rejectedRows"))));
        }
        result.sort(Comparator.comparing(ImportBatch::getStartedAt).reversed()
                .thenComparing(ImportBatch::getBatchId, Comparator.<String>reverseOrder()));
        return result.size() > limit ? new ArrayList<ImportBatch>(result.subList(0, limit)) : result;
    }

    @Override
    public List<DqCheck> dqChecks(Instant from, Instant to) {
        List<DqCheck> result = new ArrayList<DqCheck>();
        for (Map<String, Object> row : v2Store.findImports(0, ALL)) {
            String status = String.valueOf(row.get("status"));
            Instant startedAt = instant(row.get("startedAt"));
            if (!terminal(status) || startedAt == null || !inRange(startedAt, from, to)) {
                continue;
            }
            for (Map<String, Object> check : v2Store.findDqChecks(String.valueOf(row.get("batchId")))) {
                result.add(new DqCheck(String.valueOf(row.get("sourceSystem")),
                        String.valueOf(check.get("severity")),
                        Boolean.TRUE.equals(check.get("passed"))
                                || "1".equals(String.valueOf(check.get("passed")))));
            }
        }
        return result;
    }

    private List<DailyCount> dailyCounts(Map<String, Long> counts, boolean hasCategory) {
        List<DailyCount> result = new ArrayList<DailyCount>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            result.add(new DailyCount(LocalDate.parse(parts[0]),
                    hasCategory ? parts[1] : null, entry.getValue()));
        }
        return result;
    }

    private void add(Map<String, Long> counts, String key) {
        Long previous = counts.get(key);
        counts.put(key, previous == null ? 1L : previous + 1L);
    }

    private boolean inRange(Instant value, Instant from, Instant to) {
        return value != null && !value.isBefore(from) && value.isBefore(to);
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
    }

    private LocalDate day(Instant value) {
        return value.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private Instant instant(Object value) {
        if (value == null || String.valueOf(value).isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int integer(Object value) {
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }
}
