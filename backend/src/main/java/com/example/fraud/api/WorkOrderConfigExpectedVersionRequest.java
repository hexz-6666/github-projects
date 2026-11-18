package com.example.fraud.api;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class WorkOrderConfigExpectedVersionRequest {
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
}
