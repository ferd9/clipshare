package com.clipshare.report;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.common.dto.PageResponse;
import com.clipshare.report.dto.AdminReportSummary;
import com.clipshare.report.dto.ReportResponse;
import com.clipshare.report.dto.ResolveReportRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Protegido por rol en SecurityConfig (/api/admin/** exige ADMIN o MODERATOR). */
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final ReportService reportService;

    public AdminReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public PageResponse<AdminReportSummary> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Report> result = reportService.getPendingReports(page, size);
        return PageResponse.from(result, AdminReportSummary::from);
    }

    @PostMapping("/{id}/action")
    public ReportResponse resolve(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ResolveReportRequest request) {
        Report report = reportService.resolveReport(id, principal.getUser(), request.action());
        return ReportResponse.from(report);
    }
}
