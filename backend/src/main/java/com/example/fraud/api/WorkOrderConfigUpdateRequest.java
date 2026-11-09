package com.example.fraud.api;

import java.math.BigDecimal;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class WorkOrderConfigUpdateRequest {
    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 500)
    private String description;

    @NotNull
    @Min(1)
    @Max(5)
    private Integer priority;

    @NotNull
    @Valid
    private WorkOrderTriggerRequest trigger;

    @NotNull
    @DecimalMin("0.0000")
    @DecimalMax("1.0000")
    @Digits(integer = 1, fraction = 4)
    private BigDecimal riskScoreThreshold;

    @NotNull
    @Min(1)
    @Max(10080)
    private Integer slaMinutes;

    @NotNull
    private Boolean requiresReview;

    @NotBlank
    @Pattern(regexp = "^(REVIEWER|ADMIN)$", message = "assignRole must be REVIEWER or ADMIN")
    private String assignRole;

    @NotNull
    @Min(1)
    private Integer expectedVersion;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public WorkOrderTriggerRequest getTrigger() { return trigger; }
    public void setTrigger(WorkOrderTriggerRequest trigger) { this.trigger = trigger; }
    public BigDecimal getRiskScoreThreshold() { return riskScoreThreshold; }
    public void setRiskScoreThreshold(BigDecimal riskScoreThreshold) { this.riskScoreThreshold = riskScoreThreshold; }
    public Integer getSlaMinutes() { return slaMinutes; }
    public void setSlaMinutes(Integer slaMinutes) { this.slaMinutes = slaMinutes; }
    public Boolean getRequiresReview() { return requiresReview; }
    public void setRequiresReview(Boolean requiresReview) { this.requiresReview = requiresReview; }
    public String getAssignRole() { return assignRole; }
    public void setAssignRole(String assignRole) { this.assignRole = assignRole; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
}
