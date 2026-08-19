package com.clipshare.report.dto;

import com.clipshare.report.Report;
import com.clipshare.report.ReportStatus;

import java.util.UUID;

public record ReportResponse(UUID id, ReportStatus status) {
    public static ReportResponse from(Report report) {
        return new ReportResponse(report.getId(), report.getStatus());
    }
}
