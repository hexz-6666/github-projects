package com.example.fraud.domain;

import java.time.Instant;

public class WorkOrderRecord {
    private final long id;
    private final long alertId;
    private final long configId;
    private final long configVersionId;
    private final int configVersionNo;
    private final String configSnapshotJson;
    private final String assignRole;
    private final String assigneeId;
    private final WorkOrderStatus status;
    private final boolean requiresReview;
    private final int slaMinutes;
    private final Instant dueAt;
    private final Long resolutionReviewId;
    private final Instant resolvedAt;
    private final Instant cancelledAt;
    private final int version;
    private final String createdBy;
    private final String updatedBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public WorkOrderRecord(long id, long alertId, long configId, long configVersionId,
                           int configVersionNo, String configSnapshotJson, String assignRole,
                           String assigneeId, WorkOrderStatus status, boolean requiresReview,
                           int slaMinutes, Instant dueAt, Long resolutionReviewId,
                           Instant resolvedAt, Instant cancelledAt, int version,
                           String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.alertId = alertId;
        this.configId = configId;
        this.configVersionId = configVersionId;
        this.configVersionNo = configVersionNo;
        this.configSnapshotJson = configSnapshotJson;
        this.assignRole = assignRole;
        this.assigneeId = assigneeId;
        this.status = status;
        this.requiresReview = requiresReview;
        this.slaMinutes = slaMinutes;
        this.dueAt = dueAt;
        this.resolutionReviewId = resolutionReviewId;
        this.resolvedAt = resolvedAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public WorkOrderRecord withId(long newId) {
        return new WorkOrderRecord(newId, alertId, configId, configVersionId, configVersionNo,
                configSnapshotJson, assignRole, assigneeId, status, requiresReview, slaMinutes,
                dueAt, resolutionReviewId, resolvedAt, cancelledAt, version, createdBy, updatedBy,
                createdAt, updatedAt);
    }

    public WorkOrderRecord withState(String newAssigneeId, WorkOrderStatus newStatus,
                                     Long newResolutionReviewId, Instant newResolvedAt,
                                     Instant newCancelledAt, int newVersion, String newUpdatedBy,
                                     Instant newUpdatedAt) {
        return new WorkOrderRecord(id, alertId, configId, configVersionId, configVersionNo,
                configSnapshotJson, assignRole, newAssigneeId, newStatus, requiresReview, slaMinutes,
                dueAt, newResolutionReviewId, newResolvedAt, newCancelledAt, newVersion,
                createdBy, newUpdatedBy, createdAt, newUpdatedAt);
    }

    public long getId() { return id; }
    public long getAlertId() { return alertId; }
    public long getConfigId() { return configId; }
    public long getConfigVersionId() { return configVersionId; }
    public int getConfigVersionNo() { return configVersionNo; }
    public String getConfigSnapshotJson() { return configSnapshotJson; }
    public String getAssignRole() { return assignRole; }
    public String getAssigneeId() { return assigneeId; }
    public WorkOrderStatus getStatus() { return status; }
    public boolean isRequiresReview() { return requiresReview; }
    public int getSlaMinutes() { return slaMinutes; }
    public Instant getDueAt() { return dueAt; }
    public Long getResolutionReviewId() { return resolutionReviewId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getVersion() { return version; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
