package com.example.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class ScoreRecord {
    private final long scoreId;
    private final String orderId;
    private final BigDecimal score;
    private final RiskLevel riskLevel;
    private final String modelVersion;
    private final String modelType;
    private final BigDecimal threshold;
    private final List<String> ruleCodes;
    private final Instant scoredAt;

    public ScoreRecord(long scoreId, String orderId, BigDecimal score, RiskLevel riskLevel,
                       String modelVersion, String modelType, BigDecimal threshold,
                       List<String> ruleCodes, Instant scoredAt) {
        this.scoreId = scoreId;
        this.orderId = orderId;
        this.score = score;
        this.riskLevel = riskLevel;
        this.modelVersion = modelVersion;
        this.modelType = modelType;
        this.threshold = threshold;
        this.ruleCodes = ruleCodes;
        this.scoredAt = scoredAt;
    }

    public long getScoreId() { return scoreId; }
    public String getOrderId() { return orderId; }
    public BigDecimal getScore() { return score; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public String getModelVersion() { return modelVersion; }
    public String getModelType() { return modelType; }
    public BigDecimal getThreshold() { return threshold; }
    public List<String> getRuleCodes() { return ruleCodes; }
    public Instant getScoredAt() { return scoredAt; }
}
