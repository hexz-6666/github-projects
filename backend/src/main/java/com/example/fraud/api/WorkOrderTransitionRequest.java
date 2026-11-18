package com.example.fraud.api;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class WorkOrderTransitionRequest {
    @NotBlank
    private String transition;

    @NotNull
    @Min(1)
    private Integer expectedVersion;

    @Size(max = 500)
    private String note;

    @Min(1)
    private Long resolutionReviewId;

    public String getTransition() { return transition; }
    public void setTransition(String transition) { this.transition = transition; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getResolutionReviewId() { return resolutionReviewId; }
    public void setResolutionReviewId(Long resolutionReviewId) { this.resolutionReviewId = resolutionReviewId; }
}
