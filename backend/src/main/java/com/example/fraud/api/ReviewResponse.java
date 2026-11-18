package com.example.fraud.api;

import com.example.fraud.domain.AlertStatus;
import com.example.fraud.domain.ReviewResult;
import java.time.Instant;

public class ReviewResponse {
    private final long reviewId;
    private final long alertId;
    private final AlertStatus previousStatus;
    private final AlertStatus newStatus;
    private final int version;
    private final ReviewResult reviewResult;
    private final Instant reviewedAt;

    public ReviewResponse(long reviewId, long alertId, AlertStatus previousStatus,
                          AlertStatus newStatus, int version, ReviewResult reviewResult,
                          Instant reviewedAt) {
        this.reviewId = reviewId;
        this.alertId = alertId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.version = version;
        this.reviewResult = reviewResult;
        this.reviewedAt = reviewedAt;
    }

    public long getReviewId() { return reviewId; }
    public long getAlertId() { return alertId; }
    public AlertStatus getPreviousStatus() { return previousStatus; }
    public AlertStatus getNewStatus() { return newStatus; }
    public int getVersion() { return version; }
    public ReviewResult getReviewResult() { return reviewResult; }
    public Instant getReviewedAt() { return reviewedAt; }
}
