package com.clipshare.moderation;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StrikeRepository extends JpaRepository<Strike, UUID> {

    List<Strike> findAllByUserId(UUID userId);

    /**
     * Cuenta los strikes "estándar" (severity = 1) todavía vigentes de un usuario — la regla
     * de negocio de docs/SPEC.md sección 7 solo cuenta estos para la suspensión al 3ro; los de
     * severity alta (CSAM) banean de inmediato, sin esperar el conteo.
     */
    @Query("SELECT COUNT(s) FROM Strike s WHERE s.user.id = :userId AND s.severity = 1 "
            + "AND (s.expiresAt IS NULL OR s.expiresAt > :now)")
    long countActiveStandardStrikes(@Param("userId") UUID userId, @Param("now") Instant now);
}
