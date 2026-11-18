package com.example.fraud.api;

import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.ReviewRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class AlertDetailResponse {
    private final long alertId;
    private final String orderId;
    private final long scoreId;
    private final String alertStatus;
    private final String riskLevel;
    private final BigDecimal score;
    private final String modelVersion;
    private final String modelType;
    private final BigDecimal threshold;
    private final List<String> reasonCodes;
    private final int version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final OrderRecord order;
    private final List<ReviewRecord> reviews;

    public AlertDetailResponse(AlertRecord alert, OrderRecord order, List<ReviewRecord> reviews) {
        this.alertId = alert.getAlertId();
        this.orderId = alert.getOrderId();
        this.scoreId = alert.getScoreId();
        this.alertStatus = alert.getAlertStatus().name();
        this.riskLevel = alert.getRiskLevel().name();
        this.score = alert.getScore().getScore();
        this.modelVersion = alert.getScore().getModelVersion();
        this.modelType = alert.getScore().getModelType();
        this.threshold = alert.getScore().getThreshold();
        this.reasonCodes = alert.getReasonCodes();
        this.version = alert.getVersion();
        this.createdAt = alert.getCreatedAt();
        this.updatedAt = alert.getUpdatedAt();
        this.order = order;
        this.reviews = reviews;
    }

    public long getAlertId() { return alertId; }
    public String getOrderId() { return orderId; }
    public long getScoreId() { return scoreId; }
    public String getAlertStatus() { return alertStatus; }
    public String getRiskLevel() { return riskLevel; }
    public BigDecimal getScore() { return score; }
    public String getModelVersion() { return modelVersion; }
    public String getModelType() { return modelType; }
    public BigDecimal getThreshold() { return threshold; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public OrderRecord getOrder() { return order; }
    public List<ReviewRecord> getReviews() { return reviews; }
}
