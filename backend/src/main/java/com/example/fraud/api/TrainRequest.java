package com.example.fraud.api;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class TrainRequest {
    @NotBlank
    @Size(max = 64)
    private String modelVersion;

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
}
