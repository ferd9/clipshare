package com.clipshare.comment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * SHA-256(IP + salt diario) — nunca se persiste la IP en texto plano (docs/SPEC.md sección
 * 11.1). El salt rota cada día (UTC) combinando la fecha con el secreto de la app, así que
 * ni siquiera con la base de datos completa se puede recuperar la IP original ni correlacionar
 * el mismo origen entre días distintos solo con el hash — el rate-limiting/shadow-ban ya opera
 * en ventanas de horas, no necesita esa correlación de largo plazo (para eso está anon_session_id).
 */
@Component
public class IpHashService {

    private final String secret;

    public IpHashService(@Value("${app.anon-session.cookie-secret}") String secret) {
        this.secret = secret;
    }

    public String hash(String ip) {
        String salt = LocalDate.now(ZoneOffset.UTC) + ":" + secret;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest((ip == null ? "unknown" : ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** IP del cliente. En dev/local no hay proxy inverso configurado; en prod habría que
     * confiar en X-Forwarded-For solo detrás de un proxy propio conocido (no implementado acá
     * a propósito, para no abrir un vector de IP-spoofing vía header). */
    public String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
