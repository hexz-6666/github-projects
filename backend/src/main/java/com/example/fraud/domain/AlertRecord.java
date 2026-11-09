package com.example.fraud.domain;

import java.time.Instant;
import java.util.List;

public class AlertRecord {
    private final long alertId;
    private final String orderId;
    private final long scoreId;
    private final AlertStatus alertStatus;
    private final RiskLevel riskLevel;
    private final ScoreRecord score;
    private final List<String> reasonCodes;
    private final int version;
    private final Instant createdAt;
    private final Instant updatedAt;

    public AlertRecord(long alertId, String orderId, long scoreId, AlertStatus alertStatus,
                       RiskLevel riskLevel, ScoreRecord score, List<String> reasonCodes,
                       int version, Instant createdAt, Instant updatedAt) {
        this.alertId = alertId;
        this.orderId = orderId;
        this.scoreId = scoreId;
        this.alertStatus = alertStatus;
        this.riskLevel = riskLevel;
        this.score = score;
        this.reasonCodes = reasonCodes;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getAlertId() { return alertId; }
    public String getOrderId() { return orderId; }
    public long getScoreId() { return scoreId; }
    public AlertStatus getAlertStatus() { return alertStatus; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public ScoreRecord getScore() { return score; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
