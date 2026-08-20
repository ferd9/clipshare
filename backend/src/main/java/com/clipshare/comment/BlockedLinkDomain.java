package com.clipshare.comment;

import jakarta.persistence.*;

import java.time.Instant;

/** Lista de dominios conocidos como maliciosos/phishing/ilegales (docs/SPEC.md sección 11.9)
 * — alimentable a mano; ver LinkSafetyService para el chequeo dinámico complementario. */
@Entity
@Table(name = "blocked_link_domains")
public class BlockedLinkDomain {

    @Id
    private String domain;

    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BlockedLinkDomain() {
        // JPA
    }

    public BlockedLinkDomain(String domain, String reason) {
        this.domain = domain;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getDomain() {
        return domain;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
