package com.example.fraud.api;

import java.util.List;

public class WorkOrderTriggerRequest {
    private List<String> sourceSystems;
    private List<String> riskLevels;
    private List<String> alertStatuses;
    private List<String> reasonCodes;

    public List<String> getSourceSystems() { return sourceSystems; }
    public void setSourceSystems(List<String> sourceSystems) { this.sourceSystems = sourceSystems; }
    public List<String> getRiskLevels() { return riskLevels; }
    public void setRiskLevels(List<String> riskLevels) { this.riskLevels = riskLevels; }
    public List<String> getAlertStatuses() { return alertStatuses; }
    public void setAlertStatuses(List<String> alertStatuses) { this.alertStatuses = alertStatuses; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
}
