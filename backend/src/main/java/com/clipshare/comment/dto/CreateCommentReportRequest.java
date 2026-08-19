package com.clipshare.comment.dto;

import com.clipshare.report.ReportReason;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateCommentReportRequest(
        @NotNull ReportReason reason,
        String reporterName,
        @NotBlank @Email String reporterEmail,
        String description
) {
}
