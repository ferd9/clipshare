package com.clipshare.report;

import com.clipshare.clip.Clip;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Reporte público de contenido (DMCA/abuso). Los campos de la sección "DMCA formal" no son
 * opcionales por capricho: son los elementos que 17 U.S.C. §512(c)(3) exige para que un aviso
 * de retiro cuente como un DMCA notice válido (docs/SPEC.md sección 7, V4).
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clip_id", nullable = false)
    private Clip clip;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportReason reason;

    @Column(name = "reporter_name")
    private String reporterName;

    @Column(name = "reporter_email", nullable = false)
    private String reporterEmail;

    @Column(name = "reporter_address")
    private String reporterAddress;

    private String description;

    @Column(name = "good_faith_statement")
    private Boolean goodFaithStatement;

    @Column(name = "accuracy_statement")
    private Boolean accuracyStatement;

    private String signature;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ReportStatus status = ReportStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Report() {
        // JPA
    }

    public Report(Clip clip, ReportReason reason, String reporterName, String reporterEmail,
                  String reporterAddress, String description, Boolean goodFaithStatement,
                  Boolean accuracyStatement, String signature) {
        this.clip = clip;
        this.reason = reason;
        this.reporterName = reporterName;
        this.reporterEmail = reporterEmail;
        this.reporterAddress = reporterAddress;
        this.description = description;
        this.goodFaithStatement = goodFaithStatement;
        this.accuracyStatement = accuracyStatement;
        this.signature = signature;
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

    public ReportReason getReason() {
        return reason;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
