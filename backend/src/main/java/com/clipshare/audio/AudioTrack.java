package com.clipshare.audio;

import com.clipshare.clip.ModerationStatus;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Pista de audio de reemplazo para un clip (docs/SPEC.md sección 1: "el clip final puede
 * llevar una pista de audio superpuesta"; tabla ya existía desde V2__clips_media.sql, sin
 * usar hasta ahora). Reusable entre clips (de ahí {@code usageCount} — pensado como
 * "sonidos" al estilo TikTok, aunque en esta primera versión solo el propio uploader puede
 * referenciarla al crear un clip, ver AudioTrackService). El contenido de audio también es
 * copyright potencial — igual que un clip de video — por eso tiene su propio
 * {@code moderationStatus}, aunque hoy no hay un pipeline de moderación de audio real (ver
 * el TODO en AudioTrackService).
 */
@Entity
@Table(name = "audio_tracks")
public class AudioTrack {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    private String title;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "moderation_status", nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    @Column(name = "usage_count", nullable = false)
    private int usageCount = 0;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AudioTrack() {
        // JPA
    }

    public AudioTrack(User uploadedBy, String title, String filePath, int durationMs, String contentHash, String sourceUrl) {
        this.uploadedBy = uploadedBy;
        this.title = title;
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.contentHash = contentHash;
        this.sourceUrl = sourceUrl;
        // Sin pipeline de detección de copyright para audio todavía (ver TODO en
        // AudioTrackService) — se aprueba directo, igual que MockCsamHashService documenta
        // honestamente que no hace una verificación real todavía.
        this.moderationStatus = ModerationStatus.PUBLISHED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public String getContentHash() {
        return contentHash;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void incrementUsageCount() {
        this.usageCount++;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
