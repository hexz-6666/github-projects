package com.example.fraud.store;

import com.example.fraud.domain.OpsLogEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!mysql")
public class StubOpsLogStore implements OpsLogStore {
    private final List<OpsLogEvent> events = new ArrayList<OpsLogEvent>();
    private final Clock clock;
    private final int maxEvents;
    private final int retentionDays;
    private long nextId = 1L;

    public StubOpsLogStore(Clock clock,
                           @Value("${app.ops-log.max-events:50000}") int maxEvents,
                           @Value("${app.ops-log.retention-days:30}") int retentionDays) {
        this.clock = clock;
        this.maxEvents = maxEvents;
        this.retentionDays = retentionDays;
    }

    @Override
    public synchronized void append(OpsLogEvent event) {
        events.add(event.withEventId(nextId++));
        trimLocked();
    }

    @Override
    public synchronized List<OpsLogEvent> query(Query query) {
        Instant cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        List<OpsLogEvent> matches = new ArrayList<OpsLogEvent>();
        for (OpsLogEvent event : events) {
            if (event.getOccurredAt().isBefore(cutoff) || !matches(event, query)) {
                continue;
            }
            matches.add(event);
        }
        matches.sort(Comparator.comparing(OpsLogEvent::getOccurredAt)
                .thenComparingLong(OpsLogEvent::getEventId).reversed());
        int limit = query.getLimit();
        return matches.size() <= limit ? matches : new ArrayList<OpsLogEvent>(matches.subList(0, limit));
    }

    @Override
    public synchronized long count() {
        return events.size();
    }

    @Override
    public synchronized int deleteOlderThan(Instant cutoff, int limit) {
        int deleted = 0;
        for (int i = 0; i < events.size() && deleted < limit; ) {
            if (events.get(i).getOccurredAt().isBefore(cutoff)) {
                events.remove(i);
                deleted++;
            } else {
                i++;
            }
        }
        return deleted;
    }

    @Override
    public synchronized int deleteOldest(int limit) {
        int deleted = Math.min(limit, events.size());
        for (int i = 0; i < deleted; i++) {
            events.remove(0);
        }
        return deleted;
    }

    @Override
    public boolean probe() {
        return true;
    }

    private boolean matches(OpsLogEvent event, Query query) {
        if (query.getSource() != null && !query.getSource().equals(event.getSource().name())) {
            return false;
        }
        if (query.getMinLevel() != null && !event.getLevel().name().equals(query.getMinLevel())
                && rank(event.getLevel().name()) < rank(query.getMinLevel())) {
            return false;
        }
        if (query.getTaskType() != null && !query.getTaskType().equals(event.getTaskType())) {
            return false;
        }
        if (query.getTaskId() != null && !query.getTaskId().equals(event.getTaskId())) {
            return false;
        }
        if (query.getRequestId() != null && !query.getRequestId().equals(event.getRequestId())) {
            return false;
        }
        if (query.getRelatedRequestId() != null
                && !query.getRelatedRequestId().equals(event.getRelatedRequestId())) {
            return false;
        }
        if (query.getFrom() != null && event.getOccurredAt().isBefore(query.getFrom())) {
            return false;
        }
        if (query.getTo() != null && !event.getOccurredAt().isBefore(query.getTo())) {
            return false;
        }
        if (query.getBeforeOccurredAt() != null) {
            Instant before = query.getBeforeOccurredAt();
            if (event.getOccurredAt().isAfter(before)) {
                return false;
            }
            if (event.getOccurredAt().equals(before)
                    && event.getEventId() >= query.getBeforeEventId()) {
                return false;
            }
        }
        return true;
    }

    private int rank(String level) {
        if ("ERROR".equals(level)) return 40;
        if ("WARN".equals(level)) return 30;
        if ("INFO".equals(level)) return 20;
        return 10;
    }

    private void trimLocked() {
        Instant cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        events.removeIf(event -> event.getOccurredAt().isBefore(cutoff));
        while (events.size() > maxEvents) {
            events.remove(0);
        }
    }
}
