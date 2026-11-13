package com.example.fraud.domain;

import java.time.Instant;

public class OpsLogEvent {
    private final long eventId;
    private final Instant occurredAt;
    private final OpsLogSource source;
    private final OpsLogLevel level;
    private final String route;
    private final OpsLogCode code;
    private final String taskType;
    private final String taskId;
    private final String requestId;
    private final String relatedRequestId;
    private final String actorId;
    private final String buildVersion;
    private final Integer durationMs;

    public OpsLogEvent(long eventId, Instant occurredAt, OpsLogSource source, OpsLogLevel level,
                       String route, OpsLogCode code, String taskType, String taskId,
                       String requestId, String relatedRequestId, String actorId,
                       String buildVersion, Integer durationMs) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
        this.source = source;
        this.level = level;
        this.route = route;
        this.code = code;
        this.taskType = taskType;
        this.taskId = taskId;
        this.requestId = requestId;
        this.relatedRequestId = relatedRequestId;
        this.actorId = actorId;
        this.buildVersion = buildVersion;
        this.durationMs = durationMs;
    }

    public OpsLogEvent(Instant occurredAt, OpsLogSource source, OpsLogLevel level, String route,
                       OpsLogCode code, String taskType, String taskId, String requestId,
                       String relatedRequestId, String actorId, String buildVersion,
                       Integer durationMs) {
        this(0L, occurredAt, source, level, route, code, taskType, taskId, requestId,
                relatedRequestId, actorId, buildVersion, durationMs);
    }

    public OpsLogEvent withEventId(long id) {
        return new OpsLogEvent(id, occurredAt, source, level, route, code, taskType, taskId,
                requestId, relatedRequestId, actorId, buildVersion, durationMs);
    }

    public long getEventId() { return eventId; }
    public Instant getOccurredAt() { return occurredAt; }
    public OpsLogSource getSource() { return source; }
    public OpsLogLevel getLevel() { return level; }
    public String getRoute() { return route; }
    public OpsLogCode getCode() { return code; }
    public String getSummary() { return code == null ? null : code.getSummary(); }
    public String getTaskType() { return taskType; }
    public String getTaskId() { return taskId; }
    public String getRequestId() { return requestId; }
    public String getRelatedRequestId() { return relatedRequestId; }
    public String getActorId() { return actorId; }
    public String getBuildVersion() { return buildVersion; }
    public Integer getDurationMs() { return durationMs; }
}
