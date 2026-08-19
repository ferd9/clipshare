package com.clipshare.comment;

import com.clipshare.config.ApiException;
import com.clipshare.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Rate limiting de comentarios, ventana deslizante vía Redis (docs/SPEC.md sección 11.2).
 * Tres niveles: cuenta de confianza (>7 días, sin strikes) es la más permisiva, cuenta nueva
 * o con algún strike es más estricta, e invitado es la más estricta de todas — y a un
 * invitado se le exige el límite más restrictivo entre su {@code ip_hash} Y su
 * {@code anon_session_id} (una IP sola castigaría redes compartidas/NAT; la cookie sola se
 * puede borrar — usar ambas reduce falsos positivos y falsos negativos).
 */
@Component
public class CommentRateLimitService {

    private static final Duration TRUSTED_ACCOUNT_AGE = Duration.ofDays(7);

    private static final Duration TRUSTED_INTERVAL = Duration.ofSeconds(5);
    private static final Duration NEW_OR_STRUCK_INTERVAL = Duration.ofSeconds(15);
    private static final int NEW_OR_STRUCK_MAX_PER_HOUR = 10; // no expuesto por env var, ver docs/SPEC.md sección 13

    private static final Duration GUEST_INTERVAL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ShadowBanService shadowBanService;
    private final int guestMaxPerHour;
    private final int trustedMaxPerHour;

    public CommentRateLimitService(
            StringRedisTemplate redisTemplate,
            ShadowBanService shadowBanService,
            @Value("${app.comments.rate-limit.guest-per-hour}") int guestMaxPerHour,
            @Value("${app.comments.rate-limit.user-per-hour}") int trustedMaxPerHour) {
        this.redisTemplate = redisTemplate;
        this.shadowBanService = shadowBanService;
        this.guestMaxPerHour = guestMaxPerHour;
        this.trustedMaxPerHour = trustedMaxPerHour;
    }

    public void enforceForUser(User user) {
        boolean trusted = isTrusted(user);
        Duration interval = trusted ? TRUSTED_INTERVAL : NEW_OR_STRUCK_INTERVAL;
        int maxPerHour = trusted ? trustedMaxPerHour : NEW_OR_STRUCK_MAX_PER_HOUR;
        String base = "rate:comment:user:" + user.getId();
        enforceInterval(base + ":interval", interval);
        enforceHourly(base + ":hour", maxPerHour);
    }

    public void enforceForGuest(String ipHash, UUID anonSessionId) {
        // La 2ª señal de riesgo (ver ShadowBanService/ContentFilterService) reduce el límite
        // horario a la mitad antes de que una eventual 3ª señal derive en shadow-ban directo.
        boolean throttled = shadowBanService.isThrottled(ipHash, anonSessionId);
        int maxPerHour = throttled ? Math.max(1, guestMaxPerHour / 2) : guestMaxPerHour;

        enforceInterval("rate:comment:ip:" + ipHash + ":interval", GUEST_INTERVAL);
        enforceHourly("rate:comment:ip:" + ipHash + ":hour", maxPerHour);
        if (anonSessionId != null) {
            enforceInterval("rate:comment:anon:" + anonSessionId + ":interval", GUEST_INTERVAL);
            enforceHourly("rate:comment:anon:" + anonSessionId + ":hour", maxPerHour);
        }
    }

    /** Cuenta autenticada con >7 días y sin strikes — misma definición de "confianza" que
     * ContentFilterService usa para decidir si un link se permite libremente. */
    public boolean isTrusted(User user) {
        boolean oldEnough = user.getCreatedAt() != null
                && user.getCreatedAt().isBefore(Instant.now().minus(TRUSTED_ACCOUNT_AGE.toDays(), ChronoUnit.DAYS));
        return oldEnough && user.getStrikeCount() == 0;
    }

    /** SET NX con el propio intervalo como TTL: si la clave ya existe, todavía no pasó el
     * tiempo mínimo entre comentarios. No pretende ser perfectamente atómico junto con el
     * chequeo horario que sigue (dos claves separadas) — alcanza para anti-abuso, no es un
     * medidor de facturación. */
    private void enforceInterval(String key, Duration interval) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", interval);
        if (Boolean.FALSE.equals(acquired)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "COMMENT_RATE_LIMIT_INTERVAL",
                    "Estás comentando muy seguido — esperá unos segundos.");
        }
    }

    private void enforceHourly(String key, int maxPerHour) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofHours(1)); // solo al crear la key: ventana rolling, no calendario
        }
        if (count != null && count > maxPerHour) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "COMMENT_RATE_LIMIT_HOURLY",
                    "Alcanzaste el límite de comentarios por hora — probá de nuevo más tarde.");
        }
    }
}
