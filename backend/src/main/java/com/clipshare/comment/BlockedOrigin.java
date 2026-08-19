package com.clipshare.comment;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Shadow-ban durable de un origen (docs/SPEC.md sección 11.6): Redis maneja el rate-limit
 * en caliente, pero esta tabla es la fuente de verdad que sobrevive a un reinicio de Redis.
 * {@code blockedUntil = null} significa bloqueo indefinido, pendiente de revisión manual.
 */
@Entity
@Table(name = "blocked_origins")
public class BlockedOrigin {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "anon_session_id")
    private UUID anonSessionId;

    private String reason;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BlockedOrigin() {
        // JPA
    }

    public BlockedOrigin(String ipHash, UUID anonSessionId, String reason, Instant blockedUntil) {
        this.ipHash = ipHash;
        this.anonSessionId = anonSessionId;
        this.reason = reason;
        this.blockedUntil = blockedUntil;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIpHash() {
        return ipHash;
    }

    public UUID getAnonSessionId() {
        return anonSessionId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
