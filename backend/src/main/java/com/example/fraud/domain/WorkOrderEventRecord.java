package com.example.fraud.domain;

import java.time.Instant;

public class WorkOrderEventRecord {
    private final Long eventId;
    private final long workOrderId;
    private final WorkOrderEventType eventType;
    private final WorkOrderStatus fromStatus;
    private final WorkOrderStatus toStatus;
    private final String actorId;
    private final String actorRole;
    private final String note;
    private final String requestId;
    private final Instant createdAt;

    public WorkOrderEventRecord(Long eventId, long workOrderId, WorkOrderEventType eventType,
                                WorkOrderStatus fromStatus, WorkOrderStatus toStatus,
                                String actorId, String actorRole, String note, String requestId,
                                Instant createdAt) {
        this.eventId = eventId;
        this.workOrderId = workOrderId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.note = note;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public WorkOrderEventRecord withId(long newId) {
        return new WorkOrderEventRecord(newId, workOrderId, eventType, fromStatus, toStatus,
                actorId, actorRole, note, requestId, createdAt);
    }

    public Long getEventId() { return eventId; }
    public long getWorkOrderId() { return workOrderId; }
    public WorkOrderEventType getEventType() { return eventType; }
    public WorkOrderStatus getFromStatus() { return fromStatus; }
    public WorkOrderStatus getToStatus() { return toStatus; }
    public String getActorId() { return actorId; }
    public String getActorRole() { return actorRole; }
    public String getNote() { return note; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}
