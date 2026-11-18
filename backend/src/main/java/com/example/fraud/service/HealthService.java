package com.example.fraud.service;

import com.example.fraud.api.ApiException;
import com.example.fraud.api.HealthResponse;
import com.example.fraud.api.ModelHealthResponse;
import com.example.fraud.store.FraudStore;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    private final FraudStore store;
    private final ModelClient modelClient;
    private final Clock clock;

    public HealthService(FraudStore store, ModelClient modelClient, Clock clock) {
        this.store = store;
        this.modelClient = modelClient;
        this.clock = clock;
    }

    public HealthResponse aggregateHealth() {
        boolean databaseUp = false;
        Map<String, Object> database = new LinkedHashMap<String, Object>();
        if ("stub".equals(store.storageMode())) {
            database.put("status", "BYPASS");
            database.put("detail", "IN_MEMORY_CONTRACT_STORE");
        } else {
            try {
                store.checkDatabase();
                databaseUp = true;
                database.put("status", "UP");
            } catch (RuntimeException exception) {
                database.put("status", "DOWN");
            }
        }
        Map<String, Object> model = new LinkedHashMap<String, Object>();
        boolean modelUp = false;
        try {
            ModelHealthResponse health = modelClient.health();
            modelUp = true;
            model.put("status", "UP");
            model.put("modelVersion", health.getModelVersion());
            model.put("modelType", health.getModelType());
        } catch (ApiException exception) {
            model.put("status", "DOWN");
        }
        Map<String, Map<String, Object>> checks = new LinkedHashMap<String, Map<String, Object>>();
        checks.put("database", database);
        checks.put("model", model);
        boolean stub = "stub".equals(store.storageMode());
        String status = !stub && databaseUp && modelUp ? "UP" : "DEGRADED";
        return new HealthResponse(status, store.storageMode(), checks, clock.instant());
    }
}
