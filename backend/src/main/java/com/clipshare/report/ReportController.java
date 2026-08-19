package com.clipshare.report;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.report.dto.CounterNoticeRequest;
import com.clipshare.report.dto.CounterNoticeResponse;
import com.clipshare.report.dto.CreateReportRequest;
import com.clipshare.report.dto.ReportResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse create(@Valid @RequestBody CreateReportRequest request) {
        Report report = reportService.createReport(request);
        return ReportResponse.from(report);
    }

    @PostMapping("/{id}/counter-notice")
    public CounterNoticeResponse counterNotice(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody CounterNoticeRequest request) {
        DmcaCounterNotice notice = reportService.submitCounterNotice(id, principal.getUser(), request);
        return CounterNoticeResponse.from(notice);
    }
}
