package com.clipshare.clip;

import com.clipshare.config.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Límite anti-abuso para cuentas con email sin verificar (docs/SPEC.md sección 12): pueden
 * publicar clips (no las bloqueamos del todo, para no agregar fricción a probar el producto)
 * pero como máximo 3 por día — una cuenta desechable sin verificar no puede usarse para
 * volumen. El contador es compartido entre /api/clips/upload y /api/clips/from-capture
 * (es un límite de "crear clips", no uno por endpoint).
 */
@Component
public class ClipRateLimitService {

    private static final int UNVERIFIED_DAILY_LIMIT = 3;
    private static final Duration WINDOW = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;

    public ClipRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @throws ApiException 429 si la cuenta (sin verificar) ya alcanzó el límite diario. */
    public void enforceUnverifiedDailyLimit(UUID userId) {
        String key = "rate:clip-create:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW); // solo al crear la key: ventana rolling de 24h, no calendario
        }
        if (count != null && count > UNVERIFIED_DAILY_LIMIT) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_CREATE_LIMIT_EXCEEDED",
                    "Con el email sin verificar podés crear hasta " + UNVERIFIED_DAILY_LIMIT
                            + " clips por día. Verificá tu email para subir sin límite.");
        }
    }
}
