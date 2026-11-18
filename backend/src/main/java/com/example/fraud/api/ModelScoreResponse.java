package com.example.fraud.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class ModelScoreResponse {
    private String orderId;
    private BigDecimal score;
    private String riskLevel;
    private String modelVersion;
    private String modelType;
    private BigDecimal threshold;
    private List<String> ruleCodes;
    private Instant scoredAt;
    private String labelSemantics;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public List<String> getRuleCodes() { return ruleCodes; }
    public void setRuleCodes(List<String> ruleCodes) { this.ruleCodes = ruleCodes; }
    public Instant getScoredAt() { return scoredAt; }
    public void setScoredAt(Instant scoredAt) { this.scoredAt = scoredAt; }
    public String getLabelSemantics() { return labelSemantics; }
    public void setLabelSemantics(String labelSemantics) { this.labelSemantics = labelSemantics; }
}
