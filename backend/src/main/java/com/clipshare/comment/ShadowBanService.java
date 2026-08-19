package com.clipshare.comment;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Escalamiento de shadow-ban (docs/SPEC.md sección 11.6): cada "señal de riesgo" (un
 * comentario que el filtro de contenido marcó, ver ContentFilterService) suma en Redis por
 * origen; a la 2ª señal el rate-limit se reduce a la mitad (ver CommentRateLimitService), a
 * la 3ª el origen queda en shadow-ban durable en {@code blocked_origins} — sus comentarios se
 * guardan igual (status HIDDEN) pero solo el propio autor los ve, sin aviso explícito de
 * bloqueo (más efectivo contra bots/trolls que un rechazo visible que les confirma que deben
 * cambiar de estrategia).
 */
@Component
public class ShadowBanService {

    private static final Duration RISK_SIGNAL_WINDOW = Duration.ofDays(7);
    static final int THROTTLE_AT_SIGNAL_COUNT = 2;
    static final int BAN_AT_SIGNAL_COUNT = 3;
    private static final Duration AUTO_BAN_DURATION = Duration.ofHours(48);

    private final StringRedisTemplate redisTemplate;
    private final BlockedOriginRepository blockedOriginRepository;

    public ShadowBanService(StringRedisTemplate redisTemplate, BlockedOriginRepository blockedOriginRepository) {
        this.redisTemplate = redisTemplate;
        this.blockedOriginRepository = blockedOriginRepository;
    }

    public boolean isBanned(String ipHash, UUID anonSessionId) {
        return !blockedOriginRepository.findActive(ipHash, anonSessionId, Instant.now()).isEmpty();
    }

    /** true a partir de la 2ª señal de riesgo — CommentRateLimitService lo usa para aplicar
     * la mitad del límite normal antes de que el origen llegue al shadow-ban de la 3ª. */
    public boolean isThrottled(String ipHash, UUID anonSessionId) {
        return currentSignalCount(ipHash, anonSessionId) >= THROTTLE_AT_SIGNAL_COUNT;
    }

    /** @return true si esta señal disparó un shadow-ban nuevo (3ª señal). */
    public boolean recordRiskSignal(String ipHash, UUID anonSessionId, String reason) {
        long ipSignals = increment("ip:" + ipHash);
        long anonSignals = anonSessionId != null ? increment("anon:" + anonSessionId) : 0;
        long signals = Math.max(ipSignals, anonSignals);
        if (signals >= BAN_AT_SIGNAL_COUNT) {
            blockedOriginRepository.save(new BlockedOrigin(ipHash, anonSessionId, reason, Instant.now().plus(AUTO_BAN_DURATION)));
            return true;
        }
        return false;
    }

    /** Shadow-ban directo e indefinido (requiere revisión manual para levantarlo), usado tanto
     * para confirmación manual de un reporte contra un comentario de invitado (sin cuenta que
     * strikear, ver ReportService.resolveReport) como para un flood de texto idéntico detectado
     * desde múltiples orígenes (ver ContentFilterService) — ambas son señales fuertes, a
     * diferencia del shadow-ban temporal por acumulación de señales de riesgo más débiles. */
    public void banIndefinitely(String ipHash, UUID anonSessionId, String reason) {
        blockedOriginRepository.save(new BlockedOrigin(ipHash, anonSessionId, reason, null));
    }

    private long currentSignalCount(String ipHash, UUID anonSessionId) {
        long ipSignals = readCount("ip:" + ipHash);
        long anonSignals = anonSessionId != null ? readCount("anon:" + anonSessionId) : 0;
        return Math.max(ipSignals, anonSignals);
    }

    private long increment(String key) {
        String redisKey = "risk:" + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, RISK_SIGNAL_WINDOW);
        }
        return count == null ? 0 : count;
    }

    private long readCount(String key) {
        String value = redisTemplate.opsForValue().get("risk:" + key);
        return value == null ? 0 : Long.parseLong(value);
    }
}
