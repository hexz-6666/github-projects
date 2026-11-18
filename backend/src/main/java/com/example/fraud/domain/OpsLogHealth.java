package com.example.fraud.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Process-local diagnostic state. It deliberately does not claim to be a
 * cluster-wide health or metrics service; counters reset when the JVM restarts.
 */
@Component
public class OpsLogHealth {
    private volatile boolean enabled = true;
    private volatile boolean probed;
    private volatile boolean storageAvailable;
    private volatile boolean writeHealthy = true;
    private volatile Instant lastProbeAt;
    private volatile Instant lastWriteFailureAt;
    private volatile Instant lastPruneAt;
    private volatile Instant lastPruneFailureAt;
    private volatile String lastPruneErrorCode = "NONE";
    private final AtomicLong writeFailureCount = new AtomicLong();
    private final AtomicLong pruneFailureCount = new AtomicLong();

    public synchronized void disabled() {
        enabled = false;
        probed = false;
        storageAvailable = false;
    }

    public synchronized void probe(boolean available) {
        enabled = true;
        probed = true;
        storageAvailable = available;
        lastProbeAt = Instant.now();
        if (!available) {
            writeHealthy = false;
        }
    }

    public synchronized void writeSuccess() {
        writeHealthy = true;
    }

    public synchronized void writeFailure() {
        writeHealthy = false;
        writeFailureCount.incrementAndGet();
        lastWriteFailureAt = Instant.now();
    }

    public synchronized void pruneSuccess() {
        lastPruneAt = Instant.now();
    }

    public synchronized void pruneFailure(String code) {
        pruneFailureCount.incrementAndGet();
        lastPruneFailureAt = Instant.now();
        lastPruneErrorCode = code == null ? "UNKNOWN" : code;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", status());
        result.put("processLocal", true);
        result.put("countersResetOnRestart", true);
        result.put("probed", probed);
        result.put("lastProbeAt", lastProbeAt);
        result.put("lastWriteFailureAt", lastWriteFailureAt);
        result.put("writeFailureCount", writeFailureCount.get());
        result.put("lastPruneAt", lastPruneAt);
        result.put("lastPruneFailureAt", lastPruneFailureAt);
        result.put("pruneFailureCount", pruneFailureCount.get());
        result.put("lastPruneErrorCode", lastPruneErrorCode);
        return result;
    }

    private String status() {
        if (!enabled) {
            return "DISABLED";
        }
        if (!probed || !storageAvailable || !writeHealthy) {
            return "DEGRADED";
        }
        return "UP";
    }
}
