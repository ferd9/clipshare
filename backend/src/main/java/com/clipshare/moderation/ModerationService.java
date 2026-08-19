package com.clipshare.moderation;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline de moderación (docs/SPEC.md sección 10): corre siempre, tanto para OWN_UPLOAD
 * como EXTERNAL_CAPTURE, antes de que un clip pueda quedar público. Con
 * {@link MockCsamHashService} activo (default en dev) nunca encuentra coincidencias — este
 * servicio queda igual completamente conectado (extracción de frames ya la hace el worker,
 * moderation_logs/csam_hash_matches/strikes acá) para que activar la integración real de
 * NCMEC sea solo reemplazar esa implementación.
 */
@Service
public class ModerationService {

    public enum Outcome {APPROVED, REJECTED}

    public record FrameSample(Path path, Integer timestampMs) {
    }

    private final CsamHashService csamHashService;
    private final ModerationLogRepository moderationLogRepository;
    private final CsamHashMatchRepository csamHashMatchRepository;
    private final NcmecReportClient ncmecReportClient;
    private final StrikeService strikeService;
    private final ClipRepository clipRepository;
    private final ObjectMapper objectMapper;

    public ModerationService(CsamHashService csamHashService, ModerationLogRepository moderationLogRepository,
                              CsamHashMatchRepository csamHashMatchRepository, NcmecReportClient ncmecReportClient,
                              StrikeService strikeService, ClipRepository clipRepository, ObjectMapper objectMapper) {
        this.csamHashService = csamHashService;
        this.moderationLogRepository = moderationLogRepository;
        this.csamHashMatchRepository = csamHashMatchRepository;
        this.ncmecReportClient = ncmecReportClient;
        this.strikeService = strikeService;
        this.clipRepository = clipRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Outcome moderate(UUID clipId, List<FrameSample> frames) throws IOException {
        for (FrameSample frame : frames) {
            CsamHashService.FrameCheckResult result = csamHashService.checkFrame(frame.path());
            if (result.matched()) {
                recordFlagged(clipId, frame.timestampMs(), result);
                return Outcome.REJECTED;
            }
        }
        recordClean(clipId, frames.size());
        return Outcome.APPROVED;
    }

    private void recordClean(UUID clipId, int framesChecked) {
        Clip clip = clipRepository.getReferenceById(clipId);
        moderationLogRepository.save(new ModerationLog(clip, ModerationCheckType.CSAM_HASH, ModerationResult.CLEAN,
                toJson(Map.of("framesChecked", framesChecked))));
    }

    /**
     * Un match confirmado dispara, en un solo paso atómico: log de auditoría, evidencia
     * (metadata, nunca el frame en sí — ver CsamHashMatch), reporte a NCMEC y baneo inmediato
     * del dueño. El archivo del clip se descarta aparte, en el worker (ver ClipProcessingWorker).
     */
    private void recordFlagged(UUID clipId, Integer frameTimestampMs, CsamHashService.FrameCheckResult result) {
        Clip clip = clipRepository.findByIdWithOwner(clipId)
                .orElseThrow(() -> new IllegalStateException("Clip no encontrado: " + clipId));

        moderationLogRepository.save(new ModerationLog(clip, ModerationCheckType.CSAM_HASH, ModerationResult.FLAGGED,
                toJson(Map.of("matchedFrameTimestampMs", frameTimestampMs, "matchedHashSource", result.matchedHashSource()))));

        CsamHashMatch match = new CsamHashMatch(clip, frameTimestampMs, result.matchedHashSource());
        csamHashMatchRepository.save(match);

        String ncmecReportId = ncmecReportClient.report(clipId, result.matchedHashSource());
        match.setNcmecReportId(ncmecReportId);

        moderationLogRepository.save(new ModerationLog(clip, ModerationCheckType.CSAM_HASH, ModerationResult.REPORTED_NCMEC,
                toJson(Map.of("ncmecReportId", ncmecReportId == null ? "" : ncmecReportId))));

        // Severidad alta: baneo inmediato, no espera el conteo de 3 strikes (sección 7 del spec).
        strikeService.recordCsamStrike(clip.getOwner(), null);
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return null; // el detalle es solo informativo; nunca debería tumbar la moderación en sí
        }
    }
}
