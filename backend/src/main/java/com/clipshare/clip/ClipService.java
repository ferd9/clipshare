package com.clipshare.clip;

import com.clipshare.audio.AudioTrack;
import com.clipshare.audio.AudioTrackService;
import com.clipshare.config.ApiException;
import com.clipshare.storage.StorageService;
import com.clipshare.user.User;
import com.clipshare.worker.YtDlpClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Pipeline en dos fases (docs/SPEC.md, pivote a import server-side): STAGE (descargar si hace
 * falta + normalizar a un archivo "editable") deja el clip en AWAITING_EDIT; el usuario elige
 * recorte + mute/reemplazo de audio vía POST /{id}/finalize, que dispara FINALIZE (recorte
 * real + mux de audio + moderación + publicación). Mismo camino para OWN_UPLOAD y
 * EXTERNAL_CAPTURE desde STAGE en adelante — la única diferencia entre ambos es de dónde sale
 * el archivo crudo (ver ClipProcessingWorker.stageClip).
 */
@Service
public class ClipService {

    private static final int MAX_PAGE_SIZE = 50;
    /** Duración máxima del clip FINAL publicado — recorte elegido en el editor. Subido de 20s a
     * 40s por pedido de producto (deja de coincidir con el "≤20s" que menciona docs/SPEC.md
     * sección 7 como referencia original; esta constante es la fuente de verdad real). */
    public static final int MAX_CLIP_DURATION_MS = 40_000;
    /** Duración máxima de la fuente (propia o por link) antes de recortar — pedido explícito
     * del usuario, separado del límite de arriba. */
    public static final long MAX_SOURCE_DURATION_MS = 10 * 60 * 1000L;

    private final ClipRepository clipRepository;
    private final StorageService storageService;
    private final ClipQueuePublisher queuePublisher;
    private final ClipRateLimitService rateLimitService;
    private final YtDlpClient ytDlpClient;
    private final AudioTrackService audioTrackService;

    public ClipService(ClipRepository clipRepository, StorageService storageService,
                        ClipQueuePublisher queuePublisher, ClipRateLimitService rateLimitService,
                        YtDlpClient ytDlpClient, AudioTrackService audioTrackService) {
        this.clipRepository = clipRepository;
        this.storageService = storageService;
        this.queuePublisher = queuePublisher;
        this.rateLimitService = rateLimitService;
        this.ytDlpClient = ytDlpClient;
        this.audioTrackService = audioTrackService;
    }

    // ---- API: creación (docs/SPEC.md secciones 8-9) — ambas fases terminan en AWAITING_EDIT ----

    @Transactional
    public Clip uploadOwnClip(User owner, MultipartFile file) {
        validateFile(file);
        enforceUploadEligibility(owner);
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        clip.setMimeType(file.getContentType());
        clip.setFileSizeBytes(file.getSize());
        clip = clipRepository.save(clip);

        String relativePath = "raw/" + clip.getId() + "/original" + extractExtension(file.getOriginalFilename());
        storeMultipart(relativePath, file);
        clip.setFilePath(relativePath);

        enqueueStageAfterCommit(clip.getId());
        return clip;
    }

    /**
     * A diferencia de uploadOwnClip, acá no se recibe ningún archivo — el chequeo de duración
     * es solo un pre-filtro rápido (yt-dlp --print duration, unos segundos) para rechazar de
     * entrada un video obviamente demasiado largo sin siquiera encolar nada; la descarga real
     * (que sí puede tardar minutos) la hace el worker en la fase STAGE, que vuelve a validar
     * la duración real del archivo descargado por las dudas (metadata puede mentir/faltar).
     */
    @Transactional
    public Clip importFromLink(User owner, String sourceUrl, ClipPlatform sourcePlatform) {
        enforceUploadEligibility(owner);
        if (sourcePlatform == null || sourcePlatform == ClipPlatform.NONE) {
            throw ApiException.badRequest("INVALID_PLATFORM", "Plataforma de origen inválida");
        }
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw ApiException.badRequest("INVALID_SOURCE_URL", "Falta la URL de origen");
        }

        YtDlpClient.Metadata metadata;
        try {
            metadata = ytDlpClient.fetchMetadata(sourceUrl);
        } catch (YtDlpClient.YtDlpException e) {
            throw ApiException.badRequest("SOURCE_UNAVAILABLE", e.getMessage());
        }
        // metadata.durationMs() == null: la plataforma de origen no expuso duración (confirmado
        // con reels de Instagram, ver comentario en YtDlpClient.Metadata) — no bloquea el
        // import, la duración real se valida igual después de descargar (ver
        // ClipProcessingWorker.stageClip).
        if (metadata.durationMs() != null && metadata.durationMs() > MAX_SOURCE_DURATION_MS) {
            throw ApiException.badRequest("SOURCE_TOO_LONG", "El video de origen no puede superar los 10 minutos");
        }

        Clip clip = new Clip(owner, ClipSourceType.EXTERNAL_CAPTURE);
        clip.setSourcePlatform(sourcePlatform);
        clip.setSourceUrl(sourceUrl);
        clip.setSourceTitle(metadata.title());
        clip = clipRepository.save(clip);

        enqueueStageAfterCommit(clip.getId());
        return clip;
    }

    /**
     * A diferencia de versiones anteriores del spec, una cuenta con email sin verificar NO
     * está bloqueada para publicar — solo limitada a 3 clips/día (ver ClipRateLimitService
     * y docs/SPEC.md sección 12).
     */
    private void enforceUploadEligibility(User owner) {
        if (!owner.isEmailVerified()) {
            rateLimitService.enforceUnverifiedDailyLimit(owner.getId());
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "El archivo debe ser un video");
        }
    }

    private void storeMultipart(String relativePath, MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            storageService.store(relativePath, in);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo guardar el archivo");
        }
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) return ".mp4";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return ".mp4";
        String ext = originalFilename.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".mp4";
    }

    // ---- API: editor (fase AWAITING_EDIT) ----

    /** Ruta local del archivo "editable" que muestra el editor de recorte — solo el dueño, y
     * solo mientras el clip está esperando edición (antes/después el archivo no existe ahí). */
    @Transactional
    public Path getEditableFilePath(UUID clipId, User requester) {
        Clip clip = getOwnedOrThrow(clipId, requester);
        if (clip.getProcessingStatus() != ProcessingStatus.AWAITING_EDIT) {
            throw ApiException.badRequest("CLIP_NOT_EDITABLE", "El clip todavía no está listo para editar");
        }
        return storageService.resolveLocalPath(clip.getFilePath());
    }

    private static final int MAX_TITLE_LENGTH = 150;

    public record FinalizeRequest(int trimStartMs, int trimEndMs, boolean muteOriginalAudio,
                                   UUID replacementAudioTrackId, Integer replacementAudioStartMs,
                                   Integer replacementAudioEndMs, String title,
                                   Double originalAudioVolume, Double replacementAudioVolume) {
    }

    /** Rango válido del control deslizante de volumen del editor — ver FfmpegProcessor.finalizeClip. */
    private static double clampVolume(Double volume) {
        if (volume == null) return 1.0;
        if (volume < 0 || volume > 1) {
            throw ApiException.badRequest("INVALID_VOLUME", "El volumen debe estar entre 0 y 1");
        }
        return volume;
    }

    @Transactional
    public Clip finalizeClip(UUID clipId, User requester, FinalizeRequest request) {
        Clip clip = getOwnedOrThrow(clipId, requester);
        if (clip.getProcessingStatus() != ProcessingStatus.AWAITING_EDIT) {
            throw ApiException.badRequest("CLIP_NOT_EDITABLE", "El clip todavía no está listo para editar");
        }
        int rangeMs = request.trimEndMs() - request.trimStartMs();
        if (request.trimStartMs() < 0 || rangeMs <= 0 || rangeMs > MAX_CLIP_DURATION_MS) {
            throw ApiException.badRequest("INVALID_TRIM_RANGE",
                    "El recorte debe ser mayor a 0 y de hasta " + (MAX_CLIP_DURATION_MS / 1000) + "s");
        }

        AudioTrack replacement = null;
        Integer replacementAudioStartMs = null;
        if (request.replacementAudioTrackId() != null) {
            replacement = audioTrackService.getOwnedTrack(request.replacementAudioTrackId(), requester);
            replacement.incrementUsageCount(); // "sonido" elegido — cuenta al confirmarse, no recién al publicarse

            // El fragmento del AUDIO es independiente del recorte del VIDEO (arriba): un mismo
            // "sonido" reusado en otro clip puede arrancar en otro punto — ver comentario de
            // Clip.replacementAudioStartMs. Solo se valida/guarda el inicio: el final ya queda
            // acotado por la duración final del clip (el -t de salida en
            // FfmpegProcessor.finalizeClip corta ambas pistas por igual).
            if (request.replacementAudioStartMs() != null && request.replacementAudioEndMs() != null) {
                int audioRangeMs = request.replacementAudioEndMs() - request.replacementAudioStartMs();
                if (request.replacementAudioStartMs() < 0 || audioRangeMs <= 0 || audioRangeMs > MAX_CLIP_DURATION_MS) {
                    throw ApiException.badRequest("INVALID_AUDIO_TRIM_RANGE",
                            "El fragmento de audio debe ser mayor a 0 y de hasta " + (MAX_CLIP_DURATION_MS / 1000) + "s");
                }
                if (request.replacementAudioStartMs() >= replacement.getDurationMs()) {
                    throw ApiException.badRequest("INVALID_AUDIO_TRIM_RANGE", "El inicio del fragmento está fuera del audio");
                }
                replacementAudioStartMs = request.replacementAudioStartMs();
            }
        }

        String title = request.title() == null ? null : request.title().strip();
        if (title != null && title.isEmpty()) title = null; // opcional: en blanco = sin título, nunca bloquea publicar
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw ApiException.badRequest("TITLE_TOO_LONG", "El título no puede superar los " + MAX_TITLE_LENGTH + " caracteres");
        }

        clip.setTrimStartMs(request.trimStartMs());
        clip.setTrimEndMs(request.trimEndMs());
        clip.setMuteOriginalAudio(request.muteOriginalAudio());
        clip.setReplacementAudioTrack(replacement);
        clip.setReplacementAudioStartMs(replacementAudioStartMs);
        clip.setTitle(title);
        clip.setOriginalAudioVolume(clampVolume(request.originalAudioVolume()));
        clip.setReplacementAudioVolume(clampVolume(request.replacementAudioVolume()));
        // Reutiliza PROCESSING (no hace falta un valor de enum nuevo): el clip ya no está
        // esperando edición, está en cola para el recorte/mux final.
        clip.setProcessingStatus(ProcessingStatus.PROCESSING);
        clip.setProcessingError(null);

        UUID id = clip.getId();
        afterCommit(() -> queuePublisher.enqueueFinalize(id));
        return clip;
    }

    private Clip getOwnedOrThrow(UUID clipId, User requester) {
        Clip clip = getOrThrow(clipId);
        if (requester == null || !clip.getOwner().getId().equals(requester.getId())) {
            throw ApiException.notFound("CLIP_NOT_FOUND", "Clip no encontrado");
        }
        return clip;
    }

    // ---- API: feed y detalle (docs/SPEC.md sección 8) ----

    public Page<Clip> getFeed(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        return clipRepository.findByModerationStatusAndVisibilityAndDeletedAtIsNull(
                ModerationStatus.PUBLISHED, ClipVisibility.PUBLIC, pageable);
    }

    /**
     * Un clip publicado y público lo puede ver cualquiera; uno pendiente/rechazado solo su
     * dueño (permite que el frontend consulte el estado de procesamiento sin exponer
     * contenido no moderado al público — ver docs/SPEC.md sección 10).
     */
    public Clip getVisibleClip(UUID id, User requester) {
        Clip clip = clipRepository.findByIdWithOwner(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> ApiException.notFound("CLIP_NOT_FOUND", "Clip no encontrado"));

        boolean isPublic = clip.getModerationStatus() == ModerationStatus.PUBLISHED
                && clip.getVisibility() == ClipVisibility.PUBLIC;
        boolean isOwner = requester != null && requester.getId().equals(clip.getOwner().getId());

        if (!isPublic && !isOwner) {
            throw ApiException.notFound("CLIP_NOT_FOUND", "Clip no encontrado");
        }
        return clip;
    }

    // ---- Worker: transiciones de estado del pipeline (docs/SPEC.md sección 9-10) ----

    public record StageInput(ClipSourceType sourceType, String sourceUrl, String filePath) {
    }

    @Transactional
    public StageInput getStageInput(UUID clipId) {
        Clip clip = getOrThrow(clipId);
        return new StageInput(clip.getSourceType(), clip.getSourceUrl(), clip.getFilePath());
    }

    public record FinalizeInput(String editableFilePath, int trimStartMs, int trimEndMs,
                                 boolean muteOriginalAudio, String replacementAudioTrackPath,
                                 int replacementAudioStartMs, double originalAudioVolume,
                                 double replacementAudioVolume) {
    }

    @Transactional
    public FinalizeInput getFinalizeInput(UUID clipId) {
        Clip clip = getOrThrow(clipId);
        String replacementPath = clip.getReplacementAudioTrack() != null
                ? clip.getReplacementAudioTrack().getFilePath() : null;
        int replacementAudioStartMs = clip.getReplacementAudioStartMs() != null ? clip.getReplacementAudioStartMs() : 0;
        return new FinalizeInput(clip.getFilePath(), clip.getTrimStartMs(), clip.getTrimEndMs(),
                clip.isMuteOriginalAudio(), replacementPath, replacementAudioStartMs,
                clip.getOriginalAudioVolume(), clip.getReplacementAudioVolume());
    }

    @Transactional
    public void markProcessing(UUID clipId) {
        getOrThrow(clipId).setProcessingStatus(ProcessingStatus.PROCESSING);
    }

    /** Descarga (yt-dlp) completada para EXTERNAL_CAPTURE — recién ahí queda un filePath real. */
    @Transactional
    public void markRawDownloaded(UUID clipId, String rawRelativePath) {
        getOrThrow(clipId).setFilePath(rawRelativePath);
    }

    @Transactional
    public void markAwaitingEdit(UUID clipId, String editableRelativePath) {
        Clip clip = getOrThrow(clipId);
        clip.setFilePath(editableRelativePath);
        clip.setProcessingStatus(ProcessingStatus.AWAITING_EDIT);
    }

    @Transactional
    public void markFailed(UUID clipId, String message) {
        clipRepository.findById(clipId).ifPresent(clip -> {
            clip.setProcessingStatus(ProcessingStatus.FAILED);
            clip.setProcessingError(message);
        });
    }

    /**
     * Duplicado (mismo content_hash) o rechazado por moderación (docs/SPEC.md sección 10):
     * el clip nunca llega a "public/" — el motivo específico queda en moderation_logs, acá
     * el clip solo necesita reflejar que no se va a publicar.
     */
    @Transactional
    public void markRejected(UUID clipId) {
        getOrThrow(clipId).setModerationStatus(ModerationStatus.REJECTED);
    }

    public Optional<Clip> findByContentHash(String contentHash) {
        return clipRepository.findByContentHashAndDeletedAtIsNull(contentHash);
    }

    /**
     * ffmpeg ya terminó (recorte/mux de audio/normalización/probe) independientemente de lo
     * que decida la moderación después — duration/width/height/content_hash quedan seteados
     * en cualquier caso, entre otras cosas para que un reintento del mismo archivo tras un
     * rechazo lo detecte como duplicado (ver ClipProcessingWorker).
     */
    @Transactional
    public void markReady(UUID clipId, long durationMs, Integer width, Integer height, String contentHash) {
        Clip clip = getOrThrow(clipId);
        clip.setDurationMs((int) durationMs);
        clip.setWidth(width);
        clip.setHeight(height);
        clip.setContentHash(contentHash);
        clip.setProcessingStatus(ProcessingStatus.READY);
    }

    @Transactional
    public void markPublished(UUID clipId, String filePath, String thumbnailPath) {
        Clip clip = getOrThrow(clipId);
        clip.setFilePath(filePath);
        clip.setThumbnailPath(thumbnailPath);
        clip.setModerationStatus(ModerationStatus.PUBLISHED);
        clip.setPublishedAt(Instant.now());
    }

    private Clip getOrThrow(UUID clipId) {
        return clipRepository.findById(clipId)
                .orElseThrow(() -> new IllegalStateException("Clip no encontrado: " + clipId));
    }

    // NUNCA encolar dentro de una transacción todavía abierta (este método corre dentro de
    // @Transactional): el worker (otra conexión, con su propio BRPOP bloqueante de baja
    // latencia) puede llegar a buscar el clip en Postgres ANTES de que el INSERT/UPDATE esté
    // confirmado, y con READ_COMMITTED no lo va a ver todavía — se encola recién en
    // afterCommit para garantizar que la fila ya sea visible cuando el mensaje aparezca en la cola.
    private void enqueueStageAfterCommit(UUID clipId) {
        afterCommit(() -> queuePublisher.enqueueStage(clipId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // No debería pasar en el flujo real (siempre corre dentro de un @Transactional),
            // pero por robustez no se pierde el job si algún día se llama fuera de uno.
            action.run();
        }
    }
}
