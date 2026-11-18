package com.example.fraud.domain;

import java.time.Clock;
import java.time.Instant;

public enum SlaStatus {
    ON_TRACK, OVERDUE, MET, BREACHED, CANCELLED;

    public static SlaStatus of(WorkOrderRecord workOrder, Clock clock) {
        if (workOrder.getStatus() == WorkOrderStatus.CANCELLED) {
            return CANCELLED;
        }
        if (workOrder.getStatus() == WorkOrderStatus.RESOLVED) {
            Instant resolvedAt = workOrder.getResolvedAt();
            if (resolvedAt == null) {
                return BREACHED;
            }
            return resolvedAt.isAfter(workOrder.getDueAt()) ? BREACHED : MET;
        }
        return clock.instant().isAfter(workOrder.getDueAt()) ? OVERDUE : ON_TRACK;
    }
}
