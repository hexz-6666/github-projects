package com.example.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class WorkOrderConfigVersion {
    private final long id;
    private final long configId;
    private final int versionNo;
    private final int sourceVersion;
    private final String code;
    private final String name;
    private final String description;
    private final int priority;
    private final WorkOrderTrigger trigger;
    private final BigDecimal riskScoreThreshold;
    private final int slaMinutes;
    private final boolean requiresReview;
    private final String assignRole;
    private final String publishedBy;
    private final Instant publishedAt;
    private final Instant createdAt;

    public WorkOrderConfigVersion(long id, long configId, int versionNo, int sourceVersion,
                                  String code, String name, String description, int priority,
                                  WorkOrderTrigger trigger, BigDecimal riskScoreThreshold,
                                  int slaMinutes, boolean requiresReview, String assignRole,
                                  String publishedBy, Instant publishedAt, Instant createdAt) {
        this.id = id;
        this.configId = configId;
        this.versionNo = versionNo;
        this.sourceVersion = sourceVersion;
        this.code = code;
        this.name = name;
        this.description = description;
        this.priority = priority;
        this.trigger = trigger;
        this.riskScoreThreshold = riskScoreThreshold;
        this.slaMinutes = slaMinutes;
        this.requiresReview = requiresReview;
        this.assignRole = assignRole;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
    }

    public WorkOrderConfigVersion withId(long newId) {
        return new WorkOrderConfigVersion(newId, configId, versionNo, sourceVersion, code, name,
                description, priority, trigger, riskScoreThreshold, slaMinutes, requiresReview,
                assignRole, publishedBy, publishedAt, createdAt);
    }

    public WorkOrderConfigSnapshot toSnapshot() {
        return new WorkOrderConfigSnapshot(configId, versionNo, code, name, description, priority,
                trigger, riskScoreThreshold, slaMinutes, requiresReview, assignRole, publishedAt);
    }

    public long getId() { return id; }
    public long getConfigId() { return configId; }
    public int getVersionNo() { return versionNo; }
    public int getSourceVersion() { return sourceVersion; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getPriority() { return priority; }
    public WorkOrderTrigger getTrigger() { return trigger; }
    public BigDecimal getRiskScoreThreshold() { return riskScoreThreshold; }
    public int getSlaMinutes() { return slaMinutes; }
    public boolean isRequiresReview() { return requiresReview; }
    public String getAssignRole() { return assignRole; }
    public String getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
