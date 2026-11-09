package com.example.fraud.api;

import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class AlertSummaryResponse {
    private final long alertId;
    private final String orderId;
    private final AlertStatus alertStatus;
    private final RiskLevel riskLevel;
    private final BigDecimal score;
    private final List<String> reasonCodes;
    private final int version;
    private final Instant createdAt;

    public AlertSummaryResponse(AlertRecord alert) {
        this.alertId = alert.getAlertId();
        this.orderId = alert.getOrderId();
        this.alertStatus = alert.getAlertStatus();
        this.riskLevel = alert.getRiskLevel();
        this.score = alert.getScore().getScore();
        this.reasonCodes = alert.getReasonCodes();
        this.version = alert.getVersion();
        this.createdAt = alert.getCreatedAt();
    }

    public long getAlertId() { return alertId; }
    public String getOrderId() { return orderId; }
    public AlertStatus getAlertStatus() { return alertStatus; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public BigDecimal getScore() { return score; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
