package com.clipshare.moderation;

import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "strikes")
public class Strike {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private StrikeReason reason;

    // Sin @ManyToOne a Report a propósito: un strike disparado por el pipeline automático de
    // CSAM (Fase 4) no tiene un Report asociado todavía (eso llega recién con la resolución
    // de un reporte por un admin, Fase 5) — se mapea como UUID simple, nullable.
    @Column(name = "report_id")
    private UUID reportId;

    @Column(nullable = false)
    private int severity;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Strike() {
        // JPA
    }

    public Strike(User user, StrikeReason reason, UUID reportId, int severity, Instant expiresAt) {
        this.user = user;
        this.reason = reason;
        this.reportId = reportId;
        this.severity = severity;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public StrikeReason getReason() {
        return reason;
    }

    public UUID getReportId() {
        return reportId;
    }

    public int getSeverity() {
        return severity;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
