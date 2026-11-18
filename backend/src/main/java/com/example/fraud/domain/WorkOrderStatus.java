package com.example.fraud.domain;

public enum WorkOrderStatus {
    OPEN, ASSIGNED, IN_PROGRESS, RESOLVED, CANCELLED;

    public boolean isTerminal() {
        return this == RESOLVED || this == CANCELLED;
    }
}
