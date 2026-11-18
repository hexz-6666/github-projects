package com.example.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 发布时的不可变配置快照；工单行会保存其 JSON 副本。
 */
public class WorkOrderConfigSnapshot {
    private final long configId;
    private final int configVersion;
    private final String code;
    private final String name;
    private final String description;
    private final int priority;
    private final WorkOrderTrigger trigger;
    private final BigDecimal riskScoreThreshold;
    private final int slaMinutes;
    private final boolean requiresReview;
    private final String assignRole;
    private final Instant publishedAt;

    public WorkOrderConfigSnapshot(long configId, int configVersion, String code, String name,
                                   String description, int priority, WorkOrderTrigger trigger,
                                   BigDecimal riskScoreThreshold, int slaMinutes,
                                   boolean requiresReview, String assignRole, Instant publishedAt) {
        this.configId = configId;
        this.configVersion = configVersion;
        this.code = code;
        this.name = name;
        this.description = description;
        this.priority = priority;
        this.trigger = trigger;
        this.riskScoreThreshold = riskScoreThreshold;
        this.slaMinutes = slaMinutes;
        this.requiresReview = requiresReview;
        this.assignRole = assignRole;
        this.publishedAt = publishedAt;
    }

    public long getConfigId() { return configId; }
    public int getConfigVersion() { return configVersion; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getPriority() { return priority; }
    public WorkOrderTrigger getTrigger() { return trigger; }
    public BigDecimal getRiskScoreThreshold() { return riskScoreThreshold; }
    public int getSlaMinutes() { return slaMinutes; }
    public boolean isRequiresReview() { return requiresReview; }
    public String getAssignRole() { return assignRole; }
    public Instant getPublishedAt() { return publishedAt; }
}
