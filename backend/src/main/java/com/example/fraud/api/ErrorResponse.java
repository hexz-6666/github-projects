package com.example.fraud.api;

import java.time.Instant;
import java.util.List;

public class ErrorResponse {
    private final Instant timestamp;
    private final int status;
    private final String code;
    private final String message;
    private final String path;
    private final String requestId;
    private final List<ApiException.ErrorDetail> details;

    public ErrorResponse(Instant timestamp, int status, String code, String message,
                         String path, String requestId, List<ApiException.ErrorDetail> details) {
        this.timestamp = timestamp;
        this.status = status;
        this.code = code;
        this.message = message;
        this.path = path;
        this.requestId = requestId;
        this.details = details;
    }

    public Instant getTimestamp() { return timestamp; }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getPath() { return path; }
    public String getRequestId() { return requestId; }
    public List<ApiException.ErrorDetail> getDetails() { return details; }
}
