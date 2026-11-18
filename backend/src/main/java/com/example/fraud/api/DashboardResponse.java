package com.example.fraud.api;

import java.util.List;
import java.util.Map;

public class DashboardResponse {
    private final Map<String, Object> range;
    private final boolean hasData;
    private final Map<String, Object> totals;
    private final List<Map<String, Object>> buckets;
    private final Map<String, Object> orders;
    private final Map<String, Object> alerts;
    private final Map<String, Object> workOrders;
    private final Map<String, Object> reviews;
    private final Map<String, Object> snapshot;
    private final Map<String, Object> imports;
    private final Map<String, Object> dq;

    public DashboardResponse(Map<String, Object> range, boolean hasData,
                             Map<String, Object> totals, List<Map<String, Object>> buckets,
                             Map<String, Object> orders, Map<String, Object> alerts,
                             Map<String, Object> workOrders, Map<String, Object> reviews,
                             Map<String, Object> snapshot, Map<String, Object> imports,
                             Map<String, Object> dq) {
        this.range = range;
        this.hasData = hasData;
        this.totals = totals;
        this.buckets = buckets;
        this.orders = orders;
        this.alerts = alerts;
        this.workOrders = workOrders;
        this.reviews = reviews;
        this.snapshot = snapshot;
        this.imports = imports;
        this.dq = dq;
    }

    public Map<String, Object> getRange() { return range; }
    public boolean isHasData() { return hasData; }
    public Map<String, Object> getTotals() { return totals; }
    public List<Map<String, Object>> getBuckets() { return buckets; }
    public Map<String, Object> getOrders() { return orders; }
    public Map<String, Object> getAlerts() { return alerts; }
    public Map<String, Object> getWorkOrders() { return workOrders; }
    public Map<String, Object> getReviews() { return reviews; }
    public Map<String, Object> getSnapshot() { return snapshot; }
    public Map<String, Object> getImports() { return imports; }
    public Map<String, Object> getDq() { return dq; }
}
