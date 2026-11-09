package com.example.fraud.api;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class WorkOrderAssignRequest {
    @NotBlank
    @Size(max = 64)
    private String assigneeId;

    @NotNull
    @Min(1)
    private Integer expectedVersion;

    public String getAssigneeId() { return assigneeId; }
    public void setAssigneeId(String assigneeId) { this.assigneeId = assigneeId; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
}
