package com.clipshare.report.dto;

import com.clipshare.report.ReportReason;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateReportRequest(
        @NotNull UUID clipId,
        @NotNull ReportReason reason,
        String reporterName,
        @NotBlank @Email String reporterEmail,
        String reporterAddress,
        String description,
        Boolean goodFaithStatement,
        Boolean accuracyStatement,
        String signature
) {
}
