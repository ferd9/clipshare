package com.clipshare.moderation;

import com.clipshare.clip.Clip;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Auditoría de cada check de moderación por el que pasa un clip (docs/SPEC.md sección 10). */
@Entity
@Table(name = "moderation_logs")
public class ModerationLog {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clip_id", nullable = false)
    private Clip clip;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "check_type", nullable = false)
    private ModerationCheckType checkType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ModerationResult result;

    /** NULL si el check fue automático (ej. hash-matching) — no requirió revisión humana. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private User reviewer;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModerationLog() {
        // JPA
    }

    public ModerationLog(Clip clip, ModerationCheckType checkType, ModerationResult result, String details) {
        this.clip = clip;
        this.checkType = checkType;
        this.result = result;
        this.details = details;
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

    public ModerationCheckType getCheckType() {
        return checkType;
    }

    public ModerationResult getResult() {
        return result;
    }

    public User getReviewer() {
        return reviewer;
    }

    public void setReviewer(User reviewer) {
        this.reviewer = reviewer;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
