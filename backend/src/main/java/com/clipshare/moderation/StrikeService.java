package com.clipshare.moderation;

import com.clipshare.auth.RefreshToken;
import com.clipshare.auth.RefreshTokenRepository;
import com.clipshare.user.User;
import com.clipshare.user.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Política de reincidentes (docs/SPEC.md secciones 2 y 7): al 3er strike vigente de
 * severidad estándar, la cuenta pasa a SUSPENDED; cualquier strike por CSAM_CONFIRMED banea
 * de inmediato, sin esperar el conteo. Todo queda auditado en la tabla strikes.
 */
@Service
public class StrikeService {

    private static final int STANDARD_SEVERITY = 1;
    private static final int HIGH_SEVERITY = 10;
    private static final int STRIKES_BEFORE_SUSPENSION = 3;
    // Los strikes de copyright/harassment pueden prescribir (ej. 12 meses); CSAM no.
    private static final int STANDARD_STRIKE_TTL_DAYS = 365;

    private final StrikeRepository strikeRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public StrikeService(StrikeRepository strikeRepository, RefreshTokenRepository refreshTokenRepository) {
        this.strikeRepository = strikeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** Strike inmediato de máxima severidad — usado por el pipeline de CSAM (Fase 4). */
    @Transactional
    public void recordCsamStrike(User user, UUID reportId) {
        Strike strike = new Strike(user, StrikeReason.CSAM_CONFIRMED, reportId, HIGH_SEVERITY, null);
        strikeRepository.save(strike);
        user.setStatus(UserStatus.BANNED);
        revokeActiveSessions(user.getId());
    }

    /** Strike estándar — usado por copyright/harassment/otros (resolución de reportes, Fase 5). */
    @Transactional
    public void recordStandardStrike(User user, StrikeReason reason, UUID reportId) {
        Instant expiresAt = Instant.now().plus(STANDARD_STRIKE_TTL_DAYS, ChronoUnit.DAYS);
        Strike strike = new Strike(user, reason, reportId, STANDARD_SEVERITY, expiresAt);
        strikeRepository.save(strike);

        long activeStrikes = strikeRepository.countActiveStandardStrikes(user.getId(), Instant.now());
        if (activeStrikes >= STRIKES_BEFORE_SUSPENSION) {
            user.setStatus(UserStatus.SUSPENDED);
            revokeActiveSessions(user.getId());
        }
    }

    /**
     * Corta las sesiones activas al suspender/banear: un access token ya emitido deja de
     * autenticar de inmediato (ver el chequeo en JwtAuthenticationFilter), pero sin esto
     * el usuario podría igual pedir uno nuevo con el refresh token que ya tenía.
     */
    private void revokeActiveSessions(UUID userId) {
        for (RefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            token.revoke();
        }
    }
}
