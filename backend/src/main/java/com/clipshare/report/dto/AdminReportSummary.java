package com.clipshare.report.dto;

import com.clipshare.report.Report;
import com.clipshare.report.ReportReason;
import com.clipshare.report.ReportStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminReportSummary(
        UUID id,
        UUID clipId,
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
                report.getClip().getId(),
                report.getReason(),
                report.getReporterName(),
                report.getReporterEmail(),
                report.getDescription(),
                report.getStatus(),
                report.getCreatedAt());
    }
}
