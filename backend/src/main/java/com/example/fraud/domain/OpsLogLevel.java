package com.example.fraud.domain;

import java.util.ArrayList;
import java.util.List;

public enum OpsLogLevel {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);

    private final int rank;

    OpsLogLevel(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean atLeast(OpsLogLevel minimum) {
        return rank >= minimum.rank;
    }

    /** DEBUG is intentionally not queryable or emitted in M3. */
    public static OpsLogLevel queryMinimum(String value) {
        if (value == null || value.trim().isEmpty()) {
            return WARN;
        }
        OpsLogLevel level = valueOf(value.trim().toUpperCase());
        if (level == DEBUG) {
            throw new IllegalArgumentException("DEBUG is not available");
        }
        return level;
    }

    public static List<OpsLogLevel> atLeastValues(OpsLogLevel minimum) {
        List<OpsLogLevel> values = new ArrayList<OpsLogLevel>();
        for (OpsLogLevel level : values()) {
            if (level != DEBUG && level.atLeast(minimum)) {
                values.add(level);
            }
        }
        return values;
    }
}
