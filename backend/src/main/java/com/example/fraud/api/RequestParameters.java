package com.example.fraud.api;

import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.OrderStatus;
import com.example.fraud.domain.ReviewResult;
import com.example.fraud.domain.RiskLevel;
import org.springframework.http.HttpStatus;

public final class RequestParameters {
    private RequestParameters() { }

    public static int page(String value) {
        int page = integer(value, 0, "page");
        if (page < 0) {
            throw ApiException.invalid("page 必须大于或等于 0");
        }
        return page;
    }

    public static int size(String value) {
        int size = integer(value, 20, "size");
        if (size < 1 || size > 100) {
            throw ApiException.invalid("size 必须在 1 到 100 之间");
        }
        return size;
    }

    private static int integer(String value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (!value.matches("[0-9]{1,10}")) {
            throw ApiException.invalid(field + " 必须是有效整数");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ApiException.invalid(field + " 超出允许范围");
        }
    }

    public static long alertId(String value) {
        if (!value.matches("[0-9]+")) {
            throw ApiException.invalid("alertId 必须是正整数");
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw ApiException.invalid("alertId 必须是正整数");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw ApiException.invalid("alertId 超出允许范围");
        }
    }

    public static OrderStatus orderStatus(String value) {
        return enumValue(value, OrderStatus.class, "status");
    }

    public static AlertStatus alertStatus(String value) {
        return enumValue(value, AlertStatus.class, "alertStatus");
    }

    public static RiskLevel riskLevel(String value) {
        return enumValue(value, RiskLevel.class, "riskLevel");
    }

    public static ReviewResult reviewResult(String value) {
        if (value == null) {
            return null;
        }
        return enumValue(value, ReviewResult.class, "reviewResult");
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw ApiException.invalid(field + " 不是支持的枚举值");
        }
    }

    public static String keyword(String value) {
        if (value == null || value.trim().isEmpty()) {
            if (value != null && value.length() > 64) {
                throw ApiException.invalid("keyword 最长为 64 个字符");
            }
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 64) {
            throw ApiException.invalid("keyword 最长为 64 个字符");
        }
        return trimmed;
    }

    public static int offset(int page, int size) {
        long offset = (long) page * (long) size;
        if (offset > Integer.MAX_VALUE) {
            throw ApiException.invalid("分页范围过大");
        }
        return (int) offset;
    }
}
