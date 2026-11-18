package com.example.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class WorkOrderConfig {
    private final long id;
    private final String code;
    private final String name;
    private final String description;
    private final int priority;
    private final WorkOrderTrigger trigger;
    private final BigDecimal riskScoreThreshold;
    private final int slaMinutes;
    private final boolean requiresReview;
    private final String assignRole;
    private final WorkOrderConfigStatus status;
    private final int version;
    private final Integer publishedVersion;
    private final String createdBy;
    private final String updatedBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public WorkOrderConfig(long id, String code, String name, String description, int priority,
                           WorkOrderTrigger trigger, BigDecimal riskScoreThreshold, int slaMinutes,
                           boolean requiresReview, String assignRole, WorkOrderConfigStatus status,
                           int version, Integer publishedVersion, String createdBy, String updatedBy,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.priority = priority;
        this.trigger = trigger;
        this.riskScoreThreshold = riskScoreThreshold;
        this.slaMinutes = slaMinutes;
        this.requiresReview = requiresReview;
        this.assignRole = assignRole;
        this.status = status;
        this.version = version;
        this.publishedVersion = publishedVersion;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public WorkOrderConfig withId(long newId) {
        return new WorkOrderConfig(newId, code, name, description, priority, trigger,
                riskScoreThreshold, slaMinutes, requiresReview, assignRole, status, version,
                publishedVersion, createdBy, updatedBy, createdAt, updatedAt);
    }

    public WorkOrderConfig withUpdate(String newName, String newDescription, int newPriority,
                                      WorkOrderTrigger newTrigger, BigDecimal newThreshold,
                                      int newSlaMinutes, boolean newRequiresReview, String newAssignRole,
                                      WorkOrderConfigStatus newStatus, int newVersion,
                                      Integer newPublishedVersion, String newUpdatedBy, Instant newUpdatedAt) {
        return new WorkOrderConfig(id, code, newName, newDescription, newPriority, newTrigger,
                newThreshold, newSlaMinutes, newRequiresReview, newAssignRole, newStatus, newVersion,
                newPublishedVersion, createdBy, newUpdatedBy, createdAt, newUpdatedAt);
    }

    public WorkOrderConfigSnapshot toSnapshot(int versionNo, Instant publishedAt) {
        return new WorkOrderConfigSnapshot(id, versionNo, code, name, description, priority, trigger,
                riskScoreThreshold, slaMinutes, requiresReview, assignRole, publishedAt);
    }

    public long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getPriority() { return priority; }
    public WorkOrderTrigger getTrigger() { return trigger; }
    public BigDecimal getRiskScoreThreshold() { return riskScoreThreshold; }
    public int getSlaMinutes() { return slaMinutes; }
    public boolean isRequiresReview() { return requiresReview; }
    public String getAssignRole() { return assignRole; }
    public WorkOrderConfigStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public Integer getPublishedVersion() { return publishedVersion; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
