package com.example.fraud.domain;

import java.math.BigDecimal;

public enum RiskLevel {
    LOW, MEDIUM, HIGH;

    public static RiskLevel forScore(BigDecimal score) {
        if (score.compareTo(new BigDecimal("0.40")) < 0) {
            return LOW;
        }
        if (score.compareTo(new BigDecimal("0.70")) < 0) {
            return MEDIUM;
        }
        return HIGH;
    }
}
