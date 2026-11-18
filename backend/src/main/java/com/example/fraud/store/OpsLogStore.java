package com.example.fraud.store;

import com.example.fraud.domain.OpsLogEvent;
import java.time.Instant;
import java.util.List;

public interface OpsLogStore {
    void append(OpsLogEvent event);

    List<OpsLogEvent> query(Query query);

    long count();

    int deleteOlderThan(Instant cutoff, int limit);

    int deleteOldest(int limit);

    boolean probe();

    final class Query {
        private final String source;
        private final String minLevel;
        private final String taskType;
        private final String taskId;
        private final String requestId;
        private final String relatedRequestId;
        private final Instant from;
        private final Instant to;
        private final Instant beforeOccurredAt;
        private final Long beforeEventId;
        private final int limit;

        public Query(String source, String minLevel, String taskType, String taskId,
                     String requestId, String relatedRequestId, Instant from, Instant to,
                     Instant beforeOccurredAt, Long beforeEventId, int limit) {
            this.source = source;
            this.minLevel = minLevel;
            this.taskType = taskType;
            this.taskId = taskId;
            this.requestId = requestId;
            this.relatedRequestId = relatedRequestId;
            this.from = from;
            this.to = to;
            this.beforeOccurredAt = beforeOccurredAt;
            this.beforeEventId = beforeEventId;
            this.limit = limit;
        }

        public String getSource() { return source; }
        public String getMinLevel() { return minLevel; }
        public String getTaskType() { return taskType; }
        public String getTaskId() { return taskId; }
        public String getRequestId() { return requestId; }
        public String getRelatedRequestId() { return relatedRequestId; }
        public Instant getFrom() { return from; }
        public Instant getTo() { return to; }
        public Instant getBeforeOccurredAt() { return beforeOccurredAt; }
        public Long getBeforeEventId() { return beforeEventId; }
        public int getLimit() { return limit; }
    }
}
