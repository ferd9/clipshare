package com.clipshare.report;

import com.clipshare.auth.EmailService;
import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.comment.Comment;
import com.clipshare.comment.CommentRepository;
import com.clipshare.comment.CommentStatus;
import com.clipshare.comment.ShadowBanService;
import com.clipshare.comment.dto.CreateCommentReportRequest;
import com.clipshare.config.ApiException;
import com.clipshare.moderation.NcmecReportClient;
import com.clipshare.moderation.StrikeReason;
import com.clipshare.moderation.StrikeService;
import com.clipshare.report.dto.CounterNoticeRequest;
import com.clipshare.report.dto.CreateReportRequest;
import com.clipshare.storage.StorageService;
import com.clipshare.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Flujo de notice-and-takedown (docs/SPEC.md secciones 2 y 8): crear un reporte es público
 * (no requiere sesión, solo un email de contacto); la contra-notificación requiere ser el
 * dueño del clip reportado; resolverlo (Fase 5) requiere rol ADMIN/MODERATOR — ver
 * SecurityConfig y AdminReportController.
 */
@Service
public class ReportService {

    private static final int COUNTER_NOTICE_BUSINESS_DAYS = 10;
    private static final int MAX_PAGE_SIZE = 50;

    // Umbral de reportes desde orígenes distintos antes de mandar un comentario a revisión
    // automáticamente (docs/SPEC.md sección 11.7). Simplificación deliberada: cuenta reportes
    // totales, no "distintos ip_hash/usuario" — deduplicar reporteros exigiría guardar el
    // origen de cada reporte de comentario, que hoy no se persiste (solo el email declarado).
    private static final int COMMENT_REPORT_THRESHOLD = 5;

    private final ReportRepository reportRepository;
    private final ClipRepository clipRepository;
    private final CommentRepository commentRepository;
    private final DmcaCounterNoticeRepository counterNoticeRepository;
    private final StrikeService strikeService;
    private final ShadowBanService shadowBanService;
    private final NcmecReportClient ncmecReportClient;
    private final StorageService storageService;
    private final EmailService emailService;

    public ReportService(ReportRepository reportRepository, ClipRepository clipRepository,
                          CommentRepository commentRepository, DmcaCounterNoticeRepository counterNoticeRepository,
                          StrikeService strikeService, ShadowBanService shadowBanService,
                          NcmecReportClient ncmecReportClient, StorageService storageService, EmailService emailService) {
        this.reportRepository = reportRepository;
        this.clipRepository = clipRepository;
        this.commentRepository = commentRepository;
        this.counterNoticeRepository = counterNoticeRepository;
        this.strikeService = strikeService;
        this.shadowBanService = shadowBanService;
        this.ncmecReportClient = ncmecReportClient;
        this.storageService = storageService;
        this.emailService = emailService;
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

    /** Reporte de un comentario (docs/SPEC.md sección 11.7) — cualquiera puede reportar,
     * autenticado o no, igual que con clips. Sin los campos DMCA formales: no aplican a un
     * comentario de texto. */
    @Transactional
    public Report createCommentReport(UUID commentId, CreateCommentReportRequest request) {
        Comment comment = commentRepository.findById(commentId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> ApiException.notFound("COMMENT_NOT_FOUND", "Comentario no encontrado"));

        Report report = new Report(comment, request.reason(), request.reporterName(),
                request.reporterEmail(), request.description());
        reportRepository.save(report);

        comment.setReportCount(comment.getReportCount() + 1);
        if (comment.getReportCount() >= COMMENT_REPORT_THRESHOLD && comment.getStatus() == CommentStatus.VISIBLE) {
            comment.setStatus(CommentStatus.PENDING_REVIEW);
        }
        return report;
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

    // ---- Admin (docs/SPEC.md sección 14, Fase 5) ----

    public Page<Report> getPendingReports(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "createdAt")); // los más viejos primero: cola FIFO
        return reportRepository.findPending(pageable);
    }

    /**
     * CONFIRMED: retira el clip (moderation_status = TAKEN_DOWN — no se borra el archivo,
     * se retiene por si hay contra-notificación exitosa, ver docs/SPEC.md V2) y aplica un
     * strike al dueño. Un reporte de CSAM confirmado por revisión manual sigue el mismo
     * camino que el pipeline automático (Fase 4): baneo inmediato, reporte a NCMEC y borrado
     * real del archivo — con CSAM nunca se retiene el contenido, ni siquiera para una
     * eventual disputa. DISMISSED: no toca el clip ni al dueño.
     */
    @Transactional
    public Report resolveReport(UUID reportId, User admin, ReportAction action) {
        Report report = reportRepository.findByIdWithClip(reportId)
                .orElseThrow(() -> ApiException.notFound("REPORT_NOT_FOUND", "Reporte no encontrado"));

        if (report.getStatus() == ReportStatus.DISMISSED || report.getStatus() == ReportStatus.ACTIONED) {
            throw ApiException.badRequest("REPORT_ALREADY_RESOLVED", "Este reporte ya fue resuelto");
        }

        report.setResolvedBy(admin);
        report.setResolvedAt(Instant.now());

        if (action == ReportAction.DISMISSED) {
            report.setStatus(ReportStatus.DISMISSED);
            return report;
        }

        report.setStatus(ReportStatus.ACTIONED);
        if (report.getTargetType() == ReportTargetType.COMMENT) {
            resolveCommentConfirmation(report);
        } else {
            resolveClipConfirmation(report);
        }
        return report;
    }

    private void resolveClipConfirmation(Report report) {
        Clip clip = report.getClip();
        clip.setModerationStatus(ModerationStatus.TAKEN_DOWN);
        User owner = clip.getOwner();

        if (report.getReason() == ReportReason.CSAM) {
            strikeService.recordCsamStrike(owner, report.getId());
            ncmecReportClient.report(clip.getId(), "MANUAL_REVIEW");
            deleteClipFiles(clip);
        } else {
            strikeService.recordStandardStrike(owner, mapToStrikeReason(report.getReason()), report.getId());
        }

        emailService.sendTakedownNotice(owner.getEmail(), clip.getId(), report.getReason().name());
    }

    /** Mismo pipeline que un clip confirmado, salvo que un comentario de GUEST no tiene
     * cuenta que strikear/banear — ahí el shadow-ban durable del origen (docs/SPEC.md sección
     * 11.6) es el equivalente funcional de un ban de cuenta. */
    private void resolveCommentConfirmation(Report report) {
        Comment comment = report.getComment();
        comment.setStatus(CommentStatus.REMOVED);
        User author = comment.getUser();

        if (author != null) {
            if (report.getReason() == ReportReason.CSAM) {
                strikeService.recordCsamStrike(author, report.getId());
                ncmecReportClient.report(comment.getId(), "MANUAL_REVIEW");
            } else {
                strikeService.recordStandardStrike(author, mapToStrikeReason(report.getReason()), report.getId());
            }
        } else {
            shadowBanService.banIndefinitely(comment.getIpHash(), comment.getAnonSessionId(),
                    "confirmed_report:" + report.getReason());
        }
    }

    private StrikeReason mapToStrikeReason(ReportReason reason) {
        return switch (reason) {
            case COPYRIGHT_DMCA -> StrikeReason.DMCA_CONFIRMED;
            case HARASSMENT -> StrikeReason.HARASSMENT;
            case OTHER, CSAM -> StrikeReason.OTHER; // CSAM real nunca llega acá, ver resolveReport
        };
    }

    /** CSAM nunca se retiene, ni siquiera el ya publicado — a diferencia de un DMCA confirmado. */
    private void deleteClipFiles(Clip clip) {
        try {
            if (clip.getFilePath() != null) storageService.delete(clip.getFilePath());
            if (clip.getThumbnailPath() != null) storageService.delete(clip.getThumbnailPath());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo borrar el archivo del clip");
        }
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
