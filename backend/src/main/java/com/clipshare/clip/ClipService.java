package com.clipshare.clip;

import com.clipshare.clip.dto.ExternalCaptureMetadata;
import com.clipshare.config.ApiException;
import com.clipshare.storage.StorageService;
import com.clipshare.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClipService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ClipRepository clipRepository;
    private final StorageService storageService;
    private final ClipQueuePublisher queuePublisher;

    public ClipService(ClipRepository clipRepository, StorageService storageService, ClipQueuePublisher queuePublisher) {
        this.clipRepository = clipRepository;
        this.storageService = storageService;
        this.queuePublisher = queuePublisher;
    }

    // ---- API: subida propia (docs/SPEC.md secciones 8-9, Caso A) ----

    @Transactional
    public Clip uploadOwnClip(User owner, MultipartFile file) {
        validateEmailVerified(owner);
        Clip clip = new Clip(owner, ClipSourceType.OWN_UPLOAD);
        return storeAndEnqueue(clip, file);
    }

    // ---- API: captura desde link externo (docs/SPEC.md secciones 8-9, Caso B) ----

    @Transactional
    public Clip uploadExternalCapture(User owner, MultipartFile file, ExternalCaptureMetadata metadata) {
        validateEmailVerified(owner);
        validateExternalCaptureMetadata(metadata);

        Clip clip = new Clip(owner, ClipSourceType.EXTERNAL_CAPTURE);
        clip.setSourcePlatform(metadata.sourcePlatform());
        clip.setSourceUrl(metadata.sourceUrl());
        clip.setSourceExternalId(metadata.sourceExternalId());
        clip.setSourceClipStartMs(metadata.sourceClipStartMs());
        clip.setSourceClipEndMs(metadata.sourceClipEndMs());
        clip.setSourceTitle(metadata.sourceTitle());
        return storeAndEnqueue(clip, file);
    }

    private void validateEmailVerified(User owner) {
        if (!owner.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Verificá tu email antes de publicar clips");
        }
    }

    private void validateExternalCaptureMetadata(ExternalCaptureMetadata metadata) {
        if (metadata.sourcePlatform() == null || metadata.sourcePlatform() == ClipPlatform.NONE) {
            throw ApiException.badRequest("INVALID_PLATFORM", "Plataforma de origen inválida (solo YouTube, Vimeo o Twitch)");
        }
        if (metadata.sourceUrl() == null || metadata.sourceUrl().isBlank()) {
            throw ApiException.badRequest("INVALID_SOURCE_URL", "Falta la URL de origen");
        }
        int rangeMs = metadata.sourceClipEndMs() - metadata.sourceClipStartMs();
        if (metadata.sourceClipStartMs() < 0 || rangeMs <= 0 || rangeMs > 20_000) {
            throw ApiException.badRequest("INVALID_CLIP_RANGE", "El rango del clip debe ser mayor a 0 y de hasta 20s");
        }
    }

    /** Común a upload propio y captura externa: valida el archivo, lo guarda y encola el job. */
    private Clip storeAndEnqueue(Clip clip, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "El archivo debe ser un video");
        }
        clip.setMimeType(contentType);
        clip.setFileSizeBytes(file.getSize());
        clip = clipRepository.save(clip); // asigna el UUID (en memoria, ver User/UUID @GeneratedValue) antes del INSERT

        String relativePath = "raw/" + clip.getId() + "/original" + extractExtension(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            storageService.store(relativePath, in);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "No se pudo guardar el archivo");
        }
        clip.setFilePath(relativePath);

        queuePublisher.enqueue(clip.getId());
        return clip;
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) return ".mp4";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return ".mp4";
        String ext = originalFilename.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : ".mp4";
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
     * dueño (permite que UploadOwnClip.tsx consulte el estado de procesamiento sin exponer
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

    @Transactional
    public String getRawFilePath(UUID clipId) {
        return getOrThrow(clipId).getFilePath();
    }

    @Transactional
    public void markProcessing(UUID clipId) {
        getOrThrow(clipId).setProcessingStatus(ProcessingStatus.PROCESSING);
    }

    @Transactional
    public void markFailed(UUID clipId) {
        clipRepository.findById(clipId).ifPresent(clip -> clip.setProcessingStatus(ProcessingStatus.FAILED));
    }

    /** Duplicado exacto (mismo content_hash) de otro clip ya existente: se descarta, no se publica. */
    @Transactional
    public void markRejectedDuplicate(UUID clipId) {
        Clip clip = getOrThrow(clipId);
        clip.setProcessingStatus(ProcessingStatus.READY);
        clip.setModerationStatus(ModerationStatus.REJECTED);
    }

    public Optional<Clip> findByContentHash(String contentHash) {
        return clipRepository.findByContentHashAndDeletedAtIsNull(contentHash);
    }

    /**
     * Fase 2: todavía no existe el pipeline real de moderación (PDQ/CSAM llega en la Fase 4,
     * ver docs/SPEC.md sección 10) — esto es el "mock que aprueba todo automáticamente" que
     * pide la sección 14 para poder probar el pipeline completo end-to-end.
     */
    @Transactional
    public void markPublished(UUID clipId, long durationMs, Integer width, Integer height,
                               String contentHash, String filePath, String thumbnailPath) {
        Clip clip = getOrThrow(clipId);
        clip.setDurationMs((int) durationMs);
        clip.setWidth(width);
        clip.setHeight(height);
        clip.setContentHash(contentHash);
        clip.setFilePath(filePath);
        clip.setThumbnailPath(thumbnailPath);
        clip.setProcessingStatus(ProcessingStatus.READY);
        clip.setModerationStatus(ModerationStatus.PUBLISHED);
        clip.setPublishedAt(Instant.now());
    }

    private Clip getOrThrow(UUID clipId) {
        return clipRepository.findById(clipId)
                .orElseThrow(() -> new IllegalStateException("Clip no encontrado: " + clipId));
    }
}
