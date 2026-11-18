package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.DashboardResponse;
import com.example.fraud.store.DashboardStore;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private static final int MAX_DAYS = 366;
    private static final int MAX_DAY_DAYS = 90;
    private static final int IMPORT_LIMIT = 20;

    private final DashboardStore store;
    private final Clock clock;

    public DashboardService(DashboardStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public DashboardResponse overview(String fromText, String toText, String bucketText) {
        Instant asOf = clock.instant();
        Instant to = toText == null || toText.trim().isEmpty() ? null : parseInstant(toText);
        Instant from = fromText == null || fromText.trim().isEmpty() ? null : parseInstant(fromText);
        if (from == null && to == null) {
            to = asOf;
            from = to.minus(Duration.ofDays(MAX_DAYS));
        } else if (from == null || to == null) {
            throw ApiException.invalid("from 和 to 必须同时提供，或同时省略");
        }
        if (!from.isBefore(to)) {
            throw ApiException.invalid("from 必须早于 to");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw ApiException.invalid("时间范围不能超过 366 天");
        }
        String bucket = bucketText == null || bucketText.trim().isEmpty()
                ? "week" : bucketText.trim().toLowerCase();
        if (!"day".equals(bucket) && !"week".equals(bucket)) {
            throw ApiException.invalid("bucket 只允许 day 或 week");
        }
        if ("day".equals(bucket) && Duration.between(from, to).compareTo(Duration.ofDays(MAX_DAY_DAYS)) > 0) {
            throw ApiException.invalid("日粒度时间范围不能超过 90 天");
        }

        List<Bucket> buckets = buckets(from, to, bucket);
        Map<LocalDate, Integer> index = bucketIndex(buckets);
        Map<String, long[]> orderSeries = orderSeries(store.orderCounts(from, to), buckets, index);
        long[] alerts = points(store.alertCounts(from, to), buckets, index);
        long[] workOrders = points(store.workOrderCreatedCounts(from, to), buckets, index);
        long[] reviews = points(store.reviewResolvedCounts(from, to), buckets, index);

        long orderTotal = 0L;
        List<Map<String, Object>> series = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, long[]> entry : orderSeries.entrySet()) {
            long sum = sum(entry.getValue());
            orderTotal += sum;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("origin", entry.getKey());
            item.put("points", longs(entry.getValue()));
            item.put("total", sum);
            series.add(item);
        }

        Map<String, Object> orders = section(orderTotal > 0, orderTotal);
        orders.put("series", series);

        Map<String, Object> alertSection = section(sum(alerts) > 0, sum(alerts));
        alertSection.put("points", longs(alerts));

        Map<String, Object> workOrderSection = section(sum(workOrders) > 0, sum(workOrders));
        workOrderSection.put("points", longs(workOrders));

        Map<String, Long> reviewResults = store.reviewResultCounts(from, to);
        boolean reviewHasData = sum(reviews) > 0 || !reviewResults.isEmpty();
        Map<String, Object> reviewSection = section(reviewHasData, sum(reviews));
        reviewSection.put("points", longs(reviews));
        reviewSection.put("byResult", fixedCounts(
                new String[]{"CONFIRMED", "REJECTED", "INCONCLUSIVE"}, reviewResults));

        Map<String, Long> riskCounts = store.alertRiskSnapshot();
        Map<String, Long> statusCounts = store.workOrderStatusSnapshot();
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("asOf", asOf.toString());
        snapshot.put("asOfKind", "REQUEST_GENERATION_TIME");
        snapshot.put("alertsByRiskLevel", fixedCounts(
                new String[]{"LOW", "MEDIUM", "HIGH"}, riskCounts));
        snapshot.put("workOrdersByStatus", fixedCounts(
                new String[]{"OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED"},
                statusCounts));
        boolean alertsSnapshotHasData = sum(riskCounts.values()) > 0;
        boolean workOrdersSnapshotHasData = sum(statusCounts.values()) > 0;
        boolean snapshotHasData = alertsSnapshotHasData || workOrdersSnapshotHasData;
        snapshot.put("hasData", snapshotHasData);
        snapshot.put("alertsHasData", alertsSnapshotHasData);
        snapshot.put("workOrdersHasData", workOrdersSnapshotHasData);

        List<DashboardStore.ImportBatch> importRows = store.terminalImports(from, to, IMPORT_LIMIT);
        List<Map<String, Object>> imports = new ArrayList<Map<String, Object>>();
        for (DashboardStore.ImportBatch row : importRows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("batchId", row.getBatchId());
            item.put("sourceSystem", row.getSourceSystem());
            item.put("status", row.getStatus());
            item.put("startedAt", row.getStartedAt().toString());
            item.put("acceptedRows", row.getAcceptedRows());
            item.put("rejectedRows", row.getRejectedRows());
            imports.add(item);
        }
        Map<String, Object> importSection = new LinkedHashMap<String, Object>();
        importSection.put("hasData", !imports.isEmpty());
        importSection.put("batches", imports);

        List<DashboardStore.DqCheck> checks = store.dqChecks(from, to);
        DqTotals allChecks = new DqTotals();
        Map<String, DqTotals> bySource = new TreeMap<String, DqTotals>();
        for (DashboardStore.DqCheck check : checks) {
            allChecks.record(check);
            String source = check.getSourceSystem() == null ? "UNKNOWN" : check.getSourceSystem();
            DqTotals sourceTotals = bySource.get(source);
            if (sourceTotals == null) {
                sourceTotals = new DqTotals();
                bySource.put(source, sourceTotals);
            }
            sourceTotals.record(check);
        }
        Map<String, Object> dq = allChecks.toMap();
        dq.put("scope", "ALL_ENDED_BATCHES");
        List<Map<String, Object>> sourceMaps = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, DqTotals> entry : bySource.entrySet()) {
            Map<String, Object> sourceMap = entry.getValue().toMap();
            sourceMap.put("sourceSystem", entry.getKey());
            sourceMaps.add(sourceMap);
        }
        dq.put("bySource", sourceMaps);
        long totalChecks = allChecks.totalChecks;

        boolean hasData = orderTotal > 0 || sum(alerts) > 0 || sum(workOrders) > 0
                || sum(reviews) > 0 || !imports.isEmpty() || totalChecks > 0 || snapshotHasData;
        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("orders", orderTotal);
        totals.put("alerts", sum(alerts));
        totals.put("newWorkOrders", sum(workOrders));
        totals.put("reviewResolved", sum(reviews));

        Map<String, Object> range = new LinkedHashMap<String, Object>();
        range.put("from", from.toString());
        range.put("to", to.toString());
        range.put("bucket", bucket);
        range.put("timezone", "UTC");
        range.put("maxDays", MAX_DAYS);

        return new DashboardResponse(range, hasData, totals, bucketMaps(buckets),
                orders, alertSection, workOrderSection, reviewSection, snapshot, importSection, dq);
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant();
            } catch (RuntimeException exception) {
                throw ApiException.invalid("时间必须是 ISO-8601 时间，例如 2026-01-01T00:00:00Z");
            }
        }
    }

    private List<Bucket> buckets(Instant from, Instant to, String bucket) {
        LocalDate startDay = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate firstDay = "week".equals(bucket)
                ? startDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : startDay;
        List<Bucket> result = new ArrayList<Bucket>();
        Instant cursor = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        while (cursor.isBefore(to)) {
            Instant next = "week".equals(bucket)
                    ? cursor.plus(Duration.ofDays(7)) : cursor.plus(Duration.ofDays(1));
            result.add(new Bucket(cursor, next,
                    cursor.isBefore(from) || next.isAfter(to)));
            cursor = next;
        }
        return result;
    }

    private Map<LocalDate, Integer> bucketIndex(List<Bucket> buckets) {
        Map<LocalDate, Integer> result = new LinkedHashMap<LocalDate, Integer>();
        for (int i = 0; i < buckets.size(); i++) {
            result.put(buckets.get(i).start.atZone(ZoneOffset.UTC).toLocalDate(), i);
        }
        return result;
    }

    private Map<String, long[]> orderSeries(List<DashboardStore.DailyCount> rows,
                                            List<Bucket> buckets, Map<LocalDate, Integer> index) {
        Map<String, long[]> result = new TreeMap<String, long[]>();
        for (DashboardStore.DailyCount row : rows) {
            String origin = row.getCategory() == null ? "UNKNOWN" : row.getCategory();
            long[] values = result.get(origin);
            if (values == null) {
                values = new long[buckets.size()];
                result.put(origin, values);
            }
            addPoints(values, row, buckets, index);
        }
        return result;
    }

    private long[] points(List<DashboardStore.DailyCount> rows, List<Bucket> buckets,
                          Map<LocalDate, Integer> index) {
        long[] values = new long[buckets.size()];
        for (DashboardStore.DailyCount row : rows) {
            addPoints(values, row, buckets, index);
        }
        return values;
    }

    private void addPoints(long[] values, DashboardStore.DailyCount row, List<Bucket> buckets,
                           Map<LocalDate, Integer> index) {
        LocalDate day = row.getDay();
        LocalDate bucketDay = day;
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            if (!day.isBefore(bucket.start.atZone(ZoneOffset.UTC).toLocalDate())
                    && day.isBefore(bucket.end.atZone(ZoneOffset.UTC).toLocalDate())) {
                index.put(day, i);
                bucketDay = bucket.start.atZone(ZoneOffset.UTC).toLocalDate();
                break;
            }
        }
        Integer position = index.get(bucketDay);
        if (position != null && position >= 0 && position < values.length) {
            values[position] += row.getCount();
        }
    }

    private List<Map<String, Object>> bucketMaps(List<Bucket> buckets) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Bucket bucket : buckets) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("start", bucket.start.atZone(ZoneOffset.UTC).toLocalDate().toString());
            item.put("end", bucket.end.atZone(ZoneOffset.UTC).toLocalDate().toString());
            item.put("partial", bucket.partial);
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> fixedCounts(String[] keys, Map<String, Long> counts) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String key : keys) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            Long value = counts.get(key);
            item.put("key", key);
            item.put("count", value == null ? 0L : value);
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> section(boolean hasData, long total) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("hasData", hasData);
        result.put("total", total);
        return result;
    }

    private List<Long> longs(long[] values) {
        List<Long> result = new ArrayList<Long>();
        for (long value : values) {
            result.add(value);
        }
        return result;
    }

    private long sum(long[] values) {
        long result = 0L;
        for (long value : values) {
            result += value;
        }
        return result;
    }

    private long sum(Iterable<Long> values) {
        long result = 0L;
        for (Long value : values) {
            result += value == null ? 0L : value;
        }
        return result;
    }

    private Double rounded(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private static final class DqTotals {
        private long totalChecks;
        private long evaluated;
        private long passedEvaluated;
        private long infoChecks;
        private long infoFailed;
        private long failedError;
        private long failedWarn;

        private void record(DashboardStore.DqCheck check) {
            totalChecks++;
            String severity = check.getSeverity();
            if ("INFO".equals(severity)) {
                infoChecks++;
                if (!check.isPassed()) {
                    infoFailed++;
                }
            } else if ("ERROR".equals(severity) || "WARN".equals(severity)) {
                evaluated++;
                if (check.isPassed()) {
                    passedEvaluated++;
                } else if ("ERROR".equals(severity)) {
                    failedError++;
                } else {
                    failedWarn++;
                }
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("hasData", totalChecks > 0);
            result.put("totalChecks", totalChecks);
            result.put("evaluatedChecks", evaluated);
            result.put("infoChecks", infoChecks);
            result.put("infoFailed", infoFailed);
            result.put("failedError", failedError);
            result.put("failedWarn", failedWarn);
            result.put("passRate", evaluated == 0L
                    ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                    : Math.round((double) passedEvaluated / (double) evaluated * 10000.0d) / 10000.0d);
            return result;
        }
    }

    private static final class Bucket {
        private final Instant start;
        private final Instant end;
        private final boolean partial;

        private Bucket(Instant start, Instant end, boolean partial) {
            this.start = start;
            this.end = end;
            this.partial = partial;
        }
    }
}
