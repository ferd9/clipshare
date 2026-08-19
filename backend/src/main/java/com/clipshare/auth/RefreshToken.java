package com.clipshare.auth;

import com.clipshare.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistido (hasheado) para poder revocar sesiones: logout real, "cerrar sesión en todos
 * los dispositivos", o revocación forzada si se banea una cuenta. Un JWT sin esto es
 * imposible de invalidar antes de que expire (ver docs/SPEC.md sección 7, V1).
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(User user, String tokenHash, String userAgent, String ipHash, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.userAgent = userAgent;
        this.ipHash = ipHash;
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

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }
}
