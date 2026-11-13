package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.config.RequestIdFilter;
import com.example.fraud.domain.OpsLogCode;
import com.example.fraud.domain.OpsLogEvent;
import com.example.fraud.domain.OpsLogHealth;
import com.example.fraud.domain.OpsLogLevel;
import com.example.fraud.domain.OpsLogSource;
import com.example.fraud.store.OpsLogStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class OpsLogService {
    public static final String TASK_EVENT_ATTRIBUTE = OpsLogService.class.getName() + ".taskEventRecorded";
    private static final Logger FALLBACK = LoggerFactory.getLogger("ops.log.health");
    private static final int MAX_TOKEN = 64;
    private static final int MAX_TASK_TYPE = 32;
    private static final int MAX_ROUTE = 128;
    private static final int MAX_BUILD = 32;

    private final OpsLogStore store;
    private final OpsLogHealth health;
    private final Clock clock;
    private final boolean enabled;
    private final int maxEvents;
    private final int retentionDays;
    private final int pruneBatchSize;
    private final int pruneMaxBatches;
    private final int clientLimitPerMinute;
    private final int clientWindowMax;
    private final Object clientWindowLock = new Object();
    private final Map<String, Deque<Long>> clientWindows =
            new LinkedHashMap<String, Deque<Long>>();
    private final AtomicLong lastFallbackWarning = new AtomicLong(0L);

    public OpsLogService(OpsLogStore store, OpsLogHealth health, Clock clock,
                         @Value("${app.ops-log.enabled:true}") boolean enabled,
                         @Value("${app.ops-log.max-events:50000}") int maxEvents,
                         @Value("${app.ops-log.retention-days:30}") int retentionDays,
                         @Value("${app.ops-log.prune-batch-size:1000}") int pruneBatchSize,
                         @Value("${app.ops-log.prune-max-batches:10}") int pruneMaxBatches,
                         @Value("${app.ops-log.client-limit-per-minute:20}") int clientLimitPerMinute,
                         @Value("${app.ops-log.client-window-max:4096}") int clientWindowMax) {
        this.store = store;
        this.health = health;
        this.clock = clock;
        this.enabled = enabled;
        this.maxEvents = maxEvents;
        this.retentionDays = retentionDays;
        this.pruneBatchSize = pruneBatchSize;
        this.pruneMaxBatches = pruneMaxBatches;
        this.clientLimitPerMinute = clientLimitPerMinute;
        this.clientWindowMax = clientWindowMax;
    }

    @PostConstruct
    public void probeStorage() {
        if (!enabled) {
            health.disabled();
            return;
        }
        try {
            health.probe(store.probe());
        } catch (RuntimeException exception) {
            health.probe(false);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String requestId(HttpServletRequest request) {
        return request == null ? null
                : value(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), MAX_TOKEN);
    }

    public void record(OpsLogEvent event) {
        if (!enabled || event == null) {
            return;
        }
        safeAppend(event);
    }

    public void record(OpsLogSource source, OpsLogLevel level, OpsLogCode code, String route,
                       String taskType, String taskId, String requestId, String relatedRequestId,
                       String actorId, String buildVersion, Integer durationMs) {
        if (!enabled) {
            return;
        }
        record(new OpsLogEvent(clock.instant(), source,
                level == null ? code.getLevel() : level,
                normalize(route, MAX_ROUTE), code,
                normalize(taskType, MAX_TASK_TYPE), normalize(taskId, MAX_TOKEN),
                normalize(requestId, MAX_TOKEN), normalize(relatedRequestId, MAX_TOKEN),
                normalize(actorId, MAX_TOKEN), normalize(buildVersion, MAX_BUILD),
                durationMs));
    }

    public void recordAfterCommit(OpsLogEvent event) {
        if (!enabled || event == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronizationAdapter() {
                        @Override
                        public void afterCommit() {
                            safeAppend(event);
                        }
                    });
        } else {
            safeAppend(event);
        }
    }

    public Map<String, Object> query(String source, String minLevel, String taskType,
                                     String taskId, String requestId, String relatedRequestId,
                                     String from, String to, String cursor, String limit) {
        OpsLogLevel minimum;
        try {
            minimum = OpsLogLevel.queryMinimum(minLevel);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("minLevel 只能是 INFO、WARN 或 ERROR");
        }
        String normalizedSource = parseSource(source);
        String normalizedTaskType = tokenOrNull(taskType, MAX_TASK_TYPE, "taskType");
        String normalizedTaskId = tokenOrNull(taskId, MAX_TOKEN, "taskId");
        String normalizedRequestId = tokenOrNull(requestId, MAX_TOKEN, "requestId");
        String normalizedRelated = tokenOrNull(relatedRequestId, MAX_TOKEN, "relatedRequestId");
        if (normalizedRelated != null && !isUuid(normalizedRelated)) {
            throw ApiException.invalid("relatedRequestId 格式不合法");
        }
        Instant fromValue = instantOrNull(from, "from");
        Instant toValue = instantOrNull(to, "to");
        if (fromValue != null && toValue != null && !fromValue.isBefore(toValue)) {
            throw ApiException.invalid("from 必须早于 to");
        }
        Instant effectiveTo = toValue == null ? clock.instant() : toValue;
        Instant effectiveFrom = fromValue == null ? effectiveTo.minus(Duration.ofDays(Math.max(1, retentionDays)))
                : fromValue;
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(Duration.ofDays(Math.max(1, retentionDays))) > 0) {
            throw ApiException.invalid("查询时间范围不能超过保留天数");
        }
        int pageSize = parseLimit(limit);
        Cursor decoded = decodeCursor(cursor);
        OpsLogStore.Query query = new OpsLogStore.Query(normalizedSource, minimum.name(),
                normalizedTaskType, normalizedTaskId, normalizedRequestId, normalizedRelated,
                effectiveFrom, effectiveTo, decoded == null ? null : decoded.occurredAt,
                decoded == null ? null : decoded.eventId, pageSize + 1);
        List<OpsLogEvent> rows = store.query(query);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<OpsLogEvent>(rows.subList(0, pageSize));
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (OpsLogEvent event : rows) {
            items.add(map(event));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("nextCursor", hasMore && !rows.isEmpty()
                ? encodeCursor(rows.get(rows.size() - 1)) : null);
        result.put("hasMore", hasMore);
        result.put("minLevel", minimum.name());
        return result;
    }

    public void recordClientError(com.example.fraud.api.OpsClientErrorRequest request,
                                  Authentication authentication, HttpServletRequest servletRequest) {
        if (!enabled) {
            return;
        }
        OpsLogCode code;
        try {
            code = OpsLogCode.valueOf(request.getCode());
        } catch (RuntimeException exception) {
            throw ApiException.invalid("code 不在客户端错误白名单中");
        }
        if (!code.isClientAllowed()) {
            throw ApiException.invalid("code 不在客户端错误白名单中");
        }
        String actor = authentication == null ? "anonymous" : authentication.getName();
        rateLimit(actor);
        String related = normalize(request.getRelatedRequestId(), MAX_TOKEN);
        if (related != null && !isUuid(related)) {
            throw ApiException.invalid("relatedRequestId 格式不合法");
        }
        String build = normalize(request.getBuildVersion(), MAX_BUILD);
        if (build == null) {
            build = "dev";
        }
        String route = request.getRoute() == null ? null : request.getRoute().getTemplate();
        record(new OpsLogEvent(clock.instant(), OpsLogSource.FRONTEND, code.getLevel(),
                route, code, null, null, requestId(servletRequest), related, actor,
                build, null));
    }

    public Map<String, Object> health() {
        return health.snapshot();
    }

    public OpsLogHealth healthState() {
        return health;
    }

    /** Called only by the dedicated retention job, never by request handlers. */
    public void prune() {
        if (!enabled) {
            return;
        }
        try {
            Instant cutoff = clock.instant().minus(Duration.ofDays(Math.max(1, retentionDays)));
            int batches = 0;
            while (batches < pruneMaxBatches) {
                int deleted = store.deleteOlderThan(cutoff, pruneBatchSize);
                batches++;
                if (deleted < pruneBatchSize) {
                    break;
                }
            }
            long total = store.count();
            while (total > maxEvents && batches < pruneMaxBatches) {
                int deleted = store.deleteOldest(pruneBatchSize);
                batches++;
                if (deleted <= 0) {
                    break;
                }
                total -= deleted;
            }
            health.pruneSuccess();
        } catch (RuntimeException exception) {
            health.pruneFailure("PRUNE_FAILED");
            fallback("PRUNE_FAILED");
        }
    }

    private void safeAppend(OpsLogEvent event) {
        try {
            store.append(event);
            health.writeSuccess();
        } catch (RuntimeException exception) {
            // Logging is diagnostic and must never change a business outcome.
            // Catch RuntimeException only; JVM Errors are intentionally not swallowed.
            health.writeFailure();
            fallback(event.getCode() == null ? "STORE_ERROR" : event.getCode().name());
        }
    }

    private void fallback(String code) {
        long now = System.nanoTime();
        long previous = lastFallbackWarning.get();
        if (now - previous < Duration.ofMinutes(5).toNanos()
                || !lastFallbackWarning.compareAndSet(previous, now)) {
            return;
        }
        // No exception, SQL, request content, actor or stack is included.
        FALLBACK.warn("ops-log storage degraded code={}", code);
    }

    private void rateLimit(String actor) {
        long now = clock.millis();
        synchronized (clientWindowLock) {
            Deque<Long> window = clientWindows.get(actor);
            if (window != null) {
                removeExpired(window, now);
                if (window.isEmpty()) {
                    clientWindows.remove(actor);
                    window = null;
                }
            }
            if (window == null) {
                pruneExpiredWindowsLocked(now);
                if (clientWindows.size() >= clientWindowMax) {
                    // Do not evict valid windows: doing so would bypass the
                    // per-user limit. Reject only the new window.
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "客户端错误上报窗口容量已满，请稍后重试");
                }
                window = new ArrayDeque<Long>();
                clientWindows.put(actor, window);
            }
            if (window.size() >= clientLimitPerMinute) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "客户端错误上报过于频繁，请稍后重试");
            }
            window.addLast(now);
        }
    }

    private void pruneExpiredWindowsLocked(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> iterator = clientWindows.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Long> window = iterator.next().getValue();
            removeExpired(window, now);
            if (window.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void removeExpired(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= 60000L) {
            window.removeFirst();
        }
    }

    private Map<String, Object> map(OpsLogEvent event) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("eventId", event.getEventId());
        item.put("timestamp", event.getOccurredAt().toString());
        item.put("source", event.getSource().name());
        item.put("level", event.getLevel().name());
        item.put("route", event.getRoute());
        item.put("code", event.getCode().name());
        item.put("summary", event.getSummary());
        item.put("taskType", event.getTaskType());
        item.put("taskId", event.getTaskId());
        item.put("requestId", event.getRequestId());
        item.put("relatedRequestId", event.getRelatedRequestId());
        item.put("actorId", event.getActorId());
        item.put("buildVersion", event.getBuildVersion());
        item.put("durationMs", event.getDurationMs());
        return item;
    }

    private String parseSource(String value) {
        String normalized = normalize(value, 16);
        if (normalized == null) {
            return null;
        }
        try {
            return OpsLogSource.valueOf(normalized.toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid("source 必须是 FRONTEND、BACKEND、MODEL 或 TASK");
        }
    }

    private String tokenOrNull(String value, int max, String field) {
        String normalized = normalize(value, max);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw ApiException.invalid(field + " 包含非法字符");
        }
        return normalized;
    }

    private Instant instantOrNull(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw ApiException.invalid(field + " 必须是 ISO-8601 UTC 时间");
        }
    }

    private int parseLimit(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 50;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 100) {
                throw ApiException.invalid("limit 必须在 1 到 100 之间");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.invalid("limit 必须是整数");
        }
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = decoded.lastIndexOf('|');
            if (split <= 0) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new Cursor(Instant.parse(decoded.substring(0, split)),
                    Long.parseLong(decoded.substring(split + 1)));
        } catch (RuntimeException exception) {
            throw ApiException.invalid("cursor 无效");
        }
    }

    private String encodeCursor(OpsLogEvent event) {
        String value = event.getOccurredAt().toString() + "|" + event.getEventId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String normalize(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String cleaned = trimmed.replaceAll("[\\r\\n\\t\\p{Cntrl}]", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private String value(Object value, int max) {
        return value == null ? null : normalize(String.valueOf(value), max);
    }

    private boolean isUuid(String value) {
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static final class Cursor {
        private final Instant occurredAt;
        private final long eventId;

        private Cursor(Instant occurredAt, long eventId) {
            this.occurredAt = occurredAt;
            this.eventId = eventId;
        }
    }
}
