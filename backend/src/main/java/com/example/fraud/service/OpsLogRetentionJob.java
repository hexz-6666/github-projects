package com.example.fraud.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated retention task; it does not schedule or manage business work. */
@Component
public class OpsLogRetentionJob {
    private final OpsLogService opsLogService;

    public OpsLogRetentionJob(OpsLogService opsLogService) {
        this.opsLogService = opsLogService;
    }

    @Scheduled(initialDelayString = "${app.ops-log.prune-initial-delay-ms:60000}",
            fixedDelayString = "${app.ops-log.prune-interval-ms:60000}")
    public void prune() {
        opsLogService.prune();
    }
}
