package com.clipshare.report;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.config.ApiException;
import com.clipshare.report.dto.CounterNoticeRequest;
import com.clipshare.report.dto.CreateReportRequest;
import com.clipshare.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Flujo de notice-and-takedown (docs/SPEC.md secciones 2 y 8): crear un reporte es público
 * (no requiere sesión, solo un email de contacto); la contra-notificación sí requiere ser el
 * dueño del clip reportado. Resolver el reporte (acción de un admin/moderador que puede
 * generar un strike) es Fase 5 — acá solo se registra la entrada del flujo.
 */
@Service
public class ReportService {

    private static final int COUNTER_NOTICE_BUSINESS_DAYS = 10;

    private final ReportRepository reportRepository;
    private final ClipRepository clipRepository;
    private final DmcaCounterNoticeRepository counterNoticeRepository;

    public ReportService(ReportRepository reportRepository, ClipRepository clipRepository,
                          DmcaCounterNoticeRepository counterNoticeRepository) {
        this.reportRepository = reportRepository;
        this.clipRepository = clipRepository;
        this.counterNoticeRepository = counterNoticeRepository;
    }

    @Transactional
    public Report createReport(CreateReportRequest request) {
        Clip clip = clipRepository.findById(request.clipId())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> ApiException.notFound("CLIP_NOT_FOUND", "Clip no encontrado"));

        if (request.reason() == ReportReason.COPYRIGHT_DMCA) {
            validateDmcaNotice(request);
        }

        Report report = new Report(clip, request.reason(), request.reporterName(), request.reporterEmail(),
                request.reporterAddress(), request.description(), request.goodFaithStatement(),
                request.accuracyStatement(), request.signature());
        return reportRepository.save(report);
    }

    /** 17 U.S.C. §512(c)(3): sin estos elementos, un aviso de retiro no es legalmente válido. */
    private void validateDmcaNotice(CreateReportRequest request) {
        boolean incomplete = !Boolean.TRUE.equals(request.goodFaithStatement())
                || !Boolean.TRUE.equals(request.accuracyStatement())
                || isBlank(request.signature())
                || isBlank(request.reporterAddress());
        if (incomplete) {
            throw ApiException.badRequest("INCOMPLETE_DMCA_NOTICE",
                    "Un aviso DMCA requiere dirección, declaración de buena fe, declaración de exactitud y firma (17 U.S.C. §512(c)(3))");
        }
    }

    @Transactional
    public DmcaCounterNotice submitCounterNotice(UUID reportId, User submitter, CounterNoticeRequest request) {
        Report report = reportRepository.findByIdWithClip(reportId)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "Reporte no encontrado"));

        if (report.getReason() != ReportReason.COPYRIGHT_DMCA) {
            throw ApiException.badRequest("NOT_A_DMCA_REPORT", "Solo los reportes de copyright admiten contra-notificación");
        }
        if (!report.getClip().getOwner().getId().equals(submitter.getId())) {
            throw ApiException.forbidden("NOT_CLIP_OWNER", "Solo el dueño del clip puede presentar la contra-notificación");
        }

        Instant restoreEligibleAt = addBusinessDays(Instant.now(), COUNTER_NOTICE_BUSINESS_DAYS);
        DmcaCounterNotice notice = new DmcaCounterNotice(report, submitter, request.statement(),
                request.consentToJurisdiction(), request.signature(), restoreEligibleAt);
        counterNoticeRepository.save(notice);

        report.setStatus(ReportStatus.UNDER_REVIEW);
        return notice;
    }

    private Instant addBusinessDays(Instant start, int businessDays) {
        ZonedDateTime date = start.atZone(ZoneOffset.UTC);
        int added = 0;
        while (added < businessDays) {
            date = date.plusDays(1);
            DayOfWeek day = date.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date.toInstant();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
