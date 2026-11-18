package com.example.fraud.api;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

public class WorkOrderCreateRequest {
    @NotNull
    @Min(1)
    private Long configId;

    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
}
