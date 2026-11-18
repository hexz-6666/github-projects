package com.example.fraud.domain;

import java.time.Instant;

public class ReviewRecord {
    private final long reviewId;
    private final long alertId;
    private final String reviewerId;
    private final ReviewResult reviewResult;
    private final String comment;
    private final AlertStatus previousStatus;
    private final AlertStatus newStatus;
    private final Instant createdAt;

    public ReviewRecord(long reviewId, long alertId, String reviewerId,
                        ReviewResult reviewResult, String comment,
                        AlertStatus previousStatus, AlertStatus newStatus, Instant createdAt) {
        this.reviewId = reviewId;
        this.alertId = alertId;
        this.reviewerId = reviewerId;
        this.reviewResult = reviewResult;
        this.comment = comment;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.createdAt = createdAt;
    }

    public long getReviewId() { return reviewId; }
    public long getAlertId() { return alertId; }
    public String getReviewerId() { return reviewerId; }
    public ReviewResult getReviewResult() { return reviewResult; }
    public String getComment() { return comment; }
    public AlertStatus getPreviousStatus() { return previousStatus; }
    public AlertStatus getNewStatus() { return newStatus; }
    public Instant getCreatedAt() { return createdAt; }
}
