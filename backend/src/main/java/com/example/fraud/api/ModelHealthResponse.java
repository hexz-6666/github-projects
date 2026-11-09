package com.example.fraud.api;

import java.math.BigDecimal;

public class ModelHealthResponse {
    private String status;
    private String service;
    private String modelVersion;
    private String modelType;
    private BigDecimal threshold;
    private String labelSemantics;
    private Boolean ready;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public String getLabelSemantics() { return labelSemantics; }
    public void setLabelSemantics(String labelSemantics) { this.labelSemantics = labelSemantics; }
    public Boolean getReady() { return ready; }
    public void setReady(Boolean ready) { this.ready = ready; }
}
