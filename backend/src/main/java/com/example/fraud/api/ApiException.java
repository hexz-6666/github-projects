package com.example.fraud.api;

import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Collections.<ErrorDetail>emptyList());
    }

    public ApiException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<ErrorDetail> getDetails() { return details; }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    public static ApiException orderNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单或特征快照不存在");
    }

    public static ApiException alertNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "告警不存在");
    }

    public static ApiException modelUnavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "模型服务暂不可用");
    }

    public static ApiException invalidModelResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "模型响应不符合接口契约");
    }

    public static ApiException alertResolved() {
        return new ApiException(HttpStatus.CONFLICT, "ALERT_ALREADY_RESOLVED", "告警已解决，不能继续复核");
    }

    public static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "ALERT_VERSION_CONFLICT", "告警版本已变化，请刷新后重试");
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "需要登录后访问");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号无权执行该操作");
    }

    public static ApiException importSourceMissing(String source) {
        return new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_SOURCE_NOT_FOUND",
                "未找到 " + source + " 的本地数据目录，请按文档放置文件");
    }

    public static ApiException batchNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "IMPORT_BATCH_NOT_FOUND", "导入批次不存在");
    }

    public static ApiException modelServiceUnavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "模型服务暂不可用");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static class ErrorDetail {
        private final String field;
        private final String message;

        public ErrorDetail(String field, String message) {
            this.field = field;
            this.message = message;
        }

        public String getField() { return field; }
        public String getMessage() { return message; }
    }
}
