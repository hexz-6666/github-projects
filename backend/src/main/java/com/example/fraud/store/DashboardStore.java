package com.example.fraud.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate source for the M4 dashboard.
 *
 * <p>Implementations must only return aggregates; order/customer/review bodies
 * must never cross this boundary.</p>
 */
public interface DashboardStore {
    List<DailyCount> orderCounts(Instant from, Instant to);

    List<DailyCount> alertCounts(Instant from, Instant to);

    List<DailyCount> workOrderCreatedCounts(Instant from, Instant to);

    List<DailyCount> reviewResolvedCounts(Instant from, Instant to);

    Map<String, Long> reviewResultCounts(Instant from, Instant to);

    Map<String, Long> alertRiskSnapshot();

    Map<String, Long> workOrderStatusSnapshot();

    List<ImportBatch> terminalImports(Instant from, Instant to, int limit);

    List<DqCheck> dqChecks(Instant from, Instant to);

    final class DailyCount {
        private final LocalDate day;
        private final String category;
        private final long count;

        public DailyCount(LocalDate day, String category, long count) {
            this.day = day;
            this.category = category;
            this.count = count;
        }

        public LocalDate getDay() { return day; }
        public String getCategory() { return category; }
        public long getCount() { return count; }
    }

    final class ImportBatch {
        private final String batchId;
        private final String sourceSystem;
        private final String status;
        private final Instant startedAt;
        private final int acceptedRows;
        private final int rejectedRows;

        public ImportBatch(String batchId, String sourceSystem, String status, Instant startedAt,
                           int acceptedRows, int rejectedRows) {
            this.batchId = batchId;
            this.sourceSystem = sourceSystem;
            this.status = status;
            this.startedAt = startedAt;
            this.acceptedRows = acceptedRows;
            this.rejectedRows = rejectedRows;
        }

        public String getBatchId() { return batchId; }
        public String getSourceSystem() { return sourceSystem; }
        public String getStatus() { return status; }
        public Instant getStartedAt() { return startedAt; }
        public int getAcceptedRows() { return acceptedRows; }
        public int getRejectedRows() { return rejectedRows; }
    }

    final class DqCheck {
        private final String sourceSystem;
        private final String severity;
        private final boolean passed;

        public DqCheck(String sourceSystem, String severity, boolean passed) {
            this.sourceSystem = sourceSystem;
            this.severity = severity;
            this.passed = passed;
        }

        public String getSourceSystem() { return sourceSystem; }
        public String getSeverity() { return severity; }
        public boolean isPassed() { return passed; }
    }
}
