package com.clipshare.report.dto;

import com.clipshare.report.Report;
import com.clipshare.report.ReportReason;
import com.clipshare.report.ReportStatus;
import com.clipshare.report.ReportTargetType;

import java.time.Instant;
import java.util.UUID;

public record AdminReportSummary(
        UUID id,
        ReportTargetType targetType,
        UUID clipId,
        UUID commentId,
        ReportReason reason,
        String reporterName,
        String reporterEmail,
        String description,
        ReportStatus status,
        Instant createdAt
) {
    public static AdminReportSummary from(Report report) {
        return new AdminReportSummary(
                report.getId(),
                report.getTargetType(),
                report.getClip() != null ? report.getClip().getId() : null,
                report.getComment() != null ? report.getComment().getId() : null,
                report.getReason(),
                report.getReporterName(),
                report.getReporterEmail(),
                report.getDescription(),
                report.getStatus(),
                report.getCreatedAt());
    }
}
