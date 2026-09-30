package com.nitinkhandelwal.job_scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateJobRequest {

    @NotBlank
    private String idempotencyKey;

    @NotBlank
    private String taskType;

    @NotNull
    private String payload;

    @NotNull
    private LocalDateTime scheduledAt;

    private Integer priority = 0;

    private Integer maxAttempts = 3;
}
