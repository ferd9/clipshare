package com.clipshare.auth;

import com.clipshare.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Emite y valida los JWT de acceso de corta duración (ver docs/SPEC.md sección 12).
 * La sesión revocable de verdad vive en {@link RefreshToken}, no acá: un JWT nunca se
 * puede invalidar antes de expirar, por eso su vida útil se mantiene corta.
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes:20}") long accessTokenTtlMinutes) {
        this.key = Keys.hmacShaKeyFor(derive256BitKey(secret));
        this.accessTokenTtl = Duration.ofMinutes(accessTokenTtlMinutes);
    }

    /**
     * HMAC-SHA256 exige una clave de al menos 256 bits. En dev, JWT_SECRET puede ser corto
     * (ej. "change-me-in-dev"), así que derivamos siempre una clave de 32 bytes con SHA-256
     * en vez de usar los bytes crudos del secreto configurado.
     */
    private static byte[] derive256BitKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException si el token es inválido, está expirado o fue alterado. */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
