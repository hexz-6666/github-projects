package com.example.fraud.api;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ImportRequest {
    @NotBlank
    @Size(max = 32)
    private String sourceSystem;

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
}
