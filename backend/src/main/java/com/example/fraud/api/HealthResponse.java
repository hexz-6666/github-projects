package com.example.fraud.api;

import java.time.Instant;
import java.util.Map;

public class HealthResponse {
    private final String status;
    private final String service;
    private final String storageMode;
    private final Map<String, Map<String, Object>> checks;
    private final Instant timestamp;

    public HealthResponse(String status, String storageMode,
                          Map<String, Map<String, Object>> checks, Instant timestamp) {
        this.status = status;
        this.service = "fraud-api";
        this.storageMode = storageMode;
        this.checks = checks;
        this.timestamp = timestamp;
    }

    public String getStatus() { return status; }
    public String getService() { return service; }
    public String getStorageMode() { return storageMode; }
    public Map<String, Map<String, Object>> getChecks() { return checks; }
    public Instant getTimestamp() { return timestamp; }
}
