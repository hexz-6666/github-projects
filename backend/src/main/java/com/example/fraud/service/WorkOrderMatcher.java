package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.WorkOrderTriggerRequest;
import com.example.fraud.domain.AlertRecord;
import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.OrderRecord;
import com.example.fraud.domain.WorkOrderConfigSnapshot;
import com.example.fraud.domain.WorkOrderTrigger;
import com.example.fraud.store.FraudStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WorkOrderMatcher {
    private static final Set<String> SOURCE_SYSTEMS = new LinkedHashSet<String>(
            Arrays.asList("SYNTHETIC", "OLIST"));
    private static final Set<String> RISK_LEVELS = new LinkedHashSet<String>(
            Arrays.asList("LOW", "MEDIUM", "HIGH"));
    private static final Set<String> ALERT_STATUSES = new LinkedHashSet<String>(
            Arrays.asList("OPEN", "REVIEWING"));
    private static final Set<String> REASON_CODES = new LinkedHashSet<String>(
            Arrays.asList("HIGH_AMOUNT", "REPEATED_PAYMENT_ATTEMPTS", "NEW_ACCOUNT", "HIGH_VELOCITY"));

    private final FraudStore fraudStore;

    public WorkOrderMatcher(FraudStore fraudStore) {
        this.fraudStore = fraudStore;
    }

    public WorkOrderTrigger validate(WorkOrderTriggerRequest request) {
        if (request == null) {
            throw validation("trigger 不能为空");
        }
        List<String> sources = validateList("trigger.sourceSystems", request.getSourceSystems(), SOURCE_SYSTEMS, 2);
        List<String> risks = validateList("trigger.riskLevels", request.getRiskLevels(), RISK_LEVELS, 3);
        List<String> statuses = validateList("trigger.alertStatuses", request.getAlertStatuses(), ALERT_STATUSES, 2);
        List<String> reasons = validateList("trigger.reasonCodes", request.getReasonCodes(), REASON_CODES, 4);
        if (sources.isEmpty() && risks.isEmpty() && statuses.isEmpty() && reasons.isEmpty()) {
            throw validation("trigger 至少需要一个实际过滤条件");
        }
        return new WorkOrderTrigger(sources, risks, statuses, reasons);
    }

    private List<String> validateList(String field, List<String> values, Set<String> allowed, int maxSize) {
        if (values == null) {
            throw validation(field + " 不能为 null");
        }
        if (values.size() > maxSize) {
            throw validation(field + " 超过最大长度 " + maxSize);
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String value : values) {
            if (value == null || !allowed.contains(value)) {
                throw validation(field + " 包含未定义值");
            }
            if (!unique.add(value)) {
                throw validation(field + " 不允许重复值");
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(unique));
    }

    public boolean matches(AlertRecord alert, OrderRecord order, WorkOrderConfigSnapshot snapshot) {
        if (alert.getAlertStatus() != AlertStatus.OPEN && alert.getAlertStatus() != AlertStatus.REVIEWING) {
            return false;
        }
        WorkOrderTrigger trigger = snapshot.getTrigger();
        if (order == null || order.getDataOrigin() == null) {
            return false;
        }
        if (!matchesAny(trigger.getSourceSystems(), order.getDataOrigin())) {
            return false;
        }
        if (!matchesAny(trigger.getRiskLevels(), alert.getRiskLevel().name())) {
            return false;
        }
        if (!matchesAny(trigger.getAlertStatuses(), alert.getAlertStatus().name())) {
            return false;
        }
        if (!intersects(trigger.getReasonCodes(), alert.getReasonCodes())) {
            return false;
        }
        BigDecimal score = alert.getScore().getScore();
        return score != null && score.compareTo(snapshot.getRiskScoreThreshold()) >= 0;
    }

    private boolean matchesAny(List<String> selected, String actual) {
        return selected == null || selected.isEmpty() || selected.contains(actual);
    }

    private boolean intersects(List<String> selected, List<String> actual) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        for (String value : actual) {
            if (selected.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> preview(WorkOrderConfigSnapshot snapshot) {
        long totalAlerts = fraudStore.countAlerts(null, null);
        Map<String, Long> byRisk = new LinkedHashMap<String, Long>();
        Map<String, Long> bySource = new LinkedHashMap<String, Long>();
        long matched = 0;
        int pageSize = 200;
        for (int offset = 0; offset < totalAlerts; offset += pageSize) {
            List<AlertRecord> alerts = fraudStore.findAlerts(null, null, offset, pageSize);
            for (AlertRecord alert : alerts) {
                OrderRecord order = fraudStore.findOrder(alert.getOrderId()).orElse(null);
                if (!matches(alert, order, snapshot)) {
                    continue;
                }
                matched++;
                String risk = alert.getRiskLevel().name();
                String source = order.getDataOrigin();
                byRisk.put(risk, byRisk.getOrDefault(risk, 0L) + 1);
                bySource.put(source, bySource.getOrDefault(source, 0L) + 1);
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("configId", snapshot.getConfigId());
        result.put("publishedVersion", snapshot.getConfigVersion());
        result.put("totalMatched", matched);
        result.put("groupsByRiskLevel", groups(byRisk));
        result.put("groupsBySourceSystem", groups(bySource));
        return result;
    }

    private List<Map<String, Object>> groups(Map<String, Long> counts) {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("key", entry.getKey());
            group.put("count", entry.getValue());
            groups.add(group);
        }
        return groups;
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
