package com.clipshare.moderation;

import com.clipshare.clip.Clip;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Evidencia de un hallazgo de CSAM: SOLO metadata del match y el id del reporte devuelto por
 * NCMEC — nunca el contenido en sí (docs/SPEC.md sección 7, V3). El frame que generó el match
 * se descarta apenas se registra esta fila, ver ModerationService.
 */
@Entity
@Table(name = "csam_hash_matches")
public class CsamHashMatch {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clip_id", nullable = false)
    private Clip clip;

    @Column(name = "frame_timestamp_ms")
    private Integer frameTimestampMs;

    @Column(name = "matched_hash_source")
    private String matchedHashSource;

    @Column(name = "ncmec_report_id")
    private String ncmecReportId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CsamHashMatch() {
        // JPA
    }

    public CsamHashMatch(Clip clip, Integer frameTimestampMs, String matchedHashSource) {
        this.clip = clip;
        this.frameTimestampMs = frameTimestampMs;
        this.matchedHashSource = matchedHashSource;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Clip getClip() {
        return clip;
    }

    public String getNcmecReportId() {
        return ncmecReportId;
    }

    public void setNcmecReportId(String ncmecReportId) {
        this.ncmecReportId = ncmecReportId;
    }
}
