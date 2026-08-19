package com.clipshare.report;

import com.clipshare.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dmca_counter_notices")
public class DmcaCounterNotice {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by", nullable = false)
    private User submittedBy;

    @Column(nullable = false)
    private String statement;

    @Column(name = "consent_to_jurisdiction", nullable = false)
    private boolean consentToJurisdiction;

    @Column(nullable = false)
    private String signature;

    @Column(name = "restore_eligible_at")
    private Instant restoreEligibleAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DmcaCounterNotice() {
        // JPA
    }

    public DmcaCounterNotice(Report report, User submittedBy, String statement,
                              boolean consentToJurisdiction, String signature, Instant restoreEligibleAt) {
        this.report = report;
        this.submittedBy = submittedBy;
        this.statement = statement;
        this.consentToJurisdiction = consentToJurisdiction;
        this.signature = signature;
        this.restoreEligibleAt = restoreEligibleAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Instant getRestoreEligibleAt() {
        return restoreEligibleAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
