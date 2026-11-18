package com.example.fraud.api;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class WorkOrderNoteRequest {
    @NotBlank
    @Size(max = 500)
    private String note;

    @NotNull
    @Min(1)
    private Integer expectedVersion;

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
}
