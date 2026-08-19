package com.clipshare.report;

import com.clipshare.clip.Clip;
import com.clipshare.comment.Comment;
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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "target_type", nullable = false)
    private ReportTargetType targetType = ReportTargetType.CLIP;

    // Exactamente uno de los dos está seteado, según targetType (docs/SPEC.md sección 11.1,
    // chk_report_target a nivel de base de datos).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clip_id")
    private Clip clip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private Comment comment;

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

    /** Reporte sobre un comentario (docs/SPEC.md sección 11.7) — sin los campos DMCA formales, que
     * solo aplican a un aviso de retiro de contenido de video (sección 2, 17 U.S.C. §512(c)(3)). */
    public Report(Comment comment, ReportReason reason, String reporterName, String reporterEmail, String description) {
        this.targetType = ReportTargetType.COMMENT;
        this.comment = comment;
        this.reason = reason;
        this.reporterName = reporterName;
        this.reporterEmail = reporterEmail;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public Clip getClip() {
        return clip;
    }

    public Comment getComment() {
        return comment;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getReporterName() {
        return reporterName;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public String getDescription() {
        return description;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public User getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(User resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
