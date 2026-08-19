package com.clipshare.report.dto;

import com.clipshare.report.ReportAction;
import jakarta.validation.constraints.NotNull;

public record ResolveReportRequest(@NotNull ReportAction action) {
}
