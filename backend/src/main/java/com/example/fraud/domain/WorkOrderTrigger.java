package com.example.fraud.domain;

import java.util.List;

/**
 * 结构化工单匹配条件。只允许白名单字段；绝不保存任意表达式或脚本。
 */
public class WorkOrderTrigger {
    private List<String> sourceSystems;
    private List<String> riskLevels;
    private List<String> alertStatuses;
    private List<String> reasonCodes;

    public WorkOrderTrigger() {
    }

    public WorkOrderTrigger(List<String> sourceSystems, List<String> riskLevels,
                            List<String> alertStatuses, List<String> reasonCodes) {
        this.sourceSystems = sourceSystems;
        this.riskLevels = riskLevels;
        this.alertStatuses = alertStatuses;
        this.reasonCodes = reasonCodes;
    }

    public List<String> getSourceSystems() { return sourceSystems; }
    public void setSourceSystems(List<String> sourceSystems) { this.sourceSystems = sourceSystems; }
    public List<String> getRiskLevels() { return riskLevels; }
    public void setRiskLevels(List<String> riskLevels) { this.riskLevels = riskLevels; }
    public List<String> getAlertStatuses() { return alertStatuses; }
    public void setAlertStatuses(List<String> alertStatuses) { this.alertStatuses = alertStatuses; }
    public List<String> getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(List<String> reasonCodes) { this.reasonCodes = reasonCodes; }
}
