package com.clipshare.clip;

import com.clipshare.audio.AudioTrack;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clips")
public class Clip {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source_type", nullable = false)
    private ClipSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "source_platform", nullable = false)
    private ClipPlatform sourcePlatform = ClipPlatform.NONE;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "source_external_id")
    private String sourceExternalId;

    // Repotenciadas por V9__server_side_import_fields.sql: antes eran puramente informativas
    // ("a qué tramo del video original corresponde", nunca usadas para nada más). Ahora que
    // el archivo entero se descarga/sube y pasa por una fase de edición (AWAITING_EDIT), son
    // el recorte final elegido en el editor — para OWN_UPLOAD y EXTERNAL_CAPTURE por igual,
    // NULL hasta que el usuario lo confirma vía POST /api/clips/{id}/finalize.
    @Column(name = "source_clip_start_ms")
    private Integer trimStartMs;

    @Column(name = "source_clip_end_ms")
    private Integer trimEndMs;

    @Column(name = "source_title")
    private String sourceTitle;

    // Silenciar la pista de audio original al finalizar (docs/SPEC.md sección 1). Si hay
    // replacementAudioTrack, mute queda implícito sin importar este flag (no tendría sentido
    // mezclar ambas pistas) — ver FfmpegProcessor.finalizeClip.
    @Column(name = "mute_original_audio", nullable = false)
    private boolean muteOriginalAudio = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audio_track_id")
    private AudioTrack replacementAudioTrack;

    // Desde dónde (ms) arranca replacementAudioTrack para ESTE clip — vive acá y no en
    // AudioTrack porque un mismo "sonido" se puede reusar con un fragmento distinto en cada
    // clip (ver comentario de la columna en V11). Sin uso si replacementAudioTrack es null.
    @Column(name = "replacement_audio_start_ms")
    private Integer replacementAudioStartMs;

    // Nivel elegido en el editor (control deslizante, 0.0-1.0) para cada pista al MEZCLAR audio
    // original + reemplazo — ver FfmpegProcessor.finalizeClip. Sin efecto si no hay mezcla real
    // (reemplazo puro o pista muda), pero se guarda igual: mismo valor por defecto (1.0) que
    // "sin cambios", así no hace falta distinguir "no aplica" de "no lo tocó el usuario".
    @Column(name = "original_audio_volume", nullable = false)
    private double originalAudioVolume = 1.0;

    @Column(name = "replacement_audio_volume", nullable = false)
    private double replacementAudioVolume = 1.0;

    // Legible para mostrarle al usuario por qué falló (ej. "el video dura más de 10
    // minutos") — antes un FAILED no dejaba ningún rastro del motivo.
    @Column(name = "processing_error")
    private String processingError;

    // Opcional a propósito (nunca bloquea publicar) — elegido recién al finalizar, ver
    // ClipService.finalizeClip.
    @Column(name = "title")
    private String title;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "content_hash")
    private String contentHash;

    private Integer width;

    private Integer height;

    // Nullable a propósito: para OWN_UPLOAD se conoce recién cuando el worker termina de
    // procesar. Ver nota en V2__clips_media.sql y docs/SPEC.md sección 7.
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus = ProcessingStatus.QUEUED;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "moderation_status", nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ClipVisibility visibility = ClipVisibility.PUBLIC;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "like_count", nullable = false)
    private long likeCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Clip() {
        // JPA
    }

    public Clip(User owner, ClipSourceType sourceType) {
        this.owner = owner;
        this.sourceType = sourceType;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public ClipSourceType getSourceType() {
        return sourceType;
    }

    public ClipPlatform getSourcePlatform() {
        return sourcePlatform;
    }

    public void setSourcePlatform(ClipPlatform sourcePlatform) {
        this.sourcePlatform = sourcePlatform;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceExternalId() {
        return sourceExternalId;
    }

    public void setSourceExternalId(String sourceExternalId) {
        this.sourceExternalId = sourceExternalId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public Integer getTrimStartMs() {
        return trimStartMs;
    }

    public void setTrimStartMs(Integer trimStartMs) {
        this.trimStartMs = trimStartMs;
    }

    public Integer getTrimEndMs() {
        return trimEndMs;
    }

    public void setTrimEndMs(Integer trimEndMs) {
        this.trimEndMs = trimEndMs;
    }

    public boolean isMuteOriginalAudio() {
        return muteOriginalAudio;
    }

    public void setMuteOriginalAudio(boolean muteOriginalAudio) {
        this.muteOriginalAudio = muteOriginalAudio;
    }

    public AudioTrack getReplacementAudioTrack() {
        return replacementAudioTrack;
    }

    public void setReplacementAudioTrack(AudioTrack replacementAudioTrack) {
        this.replacementAudioTrack = replacementAudioTrack;
    }

    public Integer getReplacementAudioStartMs() {
        return replacementAudioStartMs;
    }

    public void setReplacementAudioStartMs(Integer replacementAudioStartMs) {
        this.replacementAudioStartMs = replacementAudioStartMs;
    }

    public double getOriginalAudioVolume() {
        return originalAudioVolume;
    }

    public void setOriginalAudioVolume(double originalAudioVolume) {
        this.originalAudioVolume = originalAudioVolume;
    }

    public double getReplacementAudioVolume() {
        return replacementAudioVolume;
    }

    public void setReplacementAudioVolume(double replacementAudioVolume) {
        this.replacementAudioVolume = replacementAudioVolume;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProcessingError() {
        return processingError;
    }

    public void setProcessingError(String processingError) {
        this.processingError = processingError;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }

    public void setThumbnailPath(String thumbnailPath) {
        this.thumbnailPath = thumbnailPath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public void setModerationStatus(ModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public ClipVisibility getVisibility() {
        return visibility;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
