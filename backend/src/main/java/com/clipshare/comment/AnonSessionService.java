package com.clipshare.comment;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Cookie anónima de sesión (docs/SPEC.md sección 11.3): httpOnly + secure, firmada con HMAC
 * (no es solo un UUID plano, para que nadie pueda fabricar/adivinar uno y suplantar el
 * historial de otro origen), sin expiración corta (~1 año). No identifica a la persona por sí
 * sola, pero permite correlacionar comportamiento del mismo navegador entre visitas — mucho
 * más efectivo contra abuso que depender solo de la IP (ver IpHashService).
 */
@Component
public class AnonSessionService {

    private static final String COOKIE_NAME = "anon_session_id";
    private static final Duration COOKIE_TTL = Duration.ofDays(365);

    private final String secret;

    public AnonSessionService(@Value("${app.anon-session.cookie-secret}") String secret) {
        this.secret = secret;
    }

    /** Lee y valida la cookie existente; NO escribe nada. Usar para lecturas (ej. listar
     * comentarios) donde no hace falta crear una sesión nueva solo por mirar. */
    public Optional<UUID> readValid(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return parseAndVerify(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** Devuelve la sesión existente si es válida, o crea una nueva y la setea en la response. */
    public UUID ensureSession(HttpServletRequest request, HttpServletResponse response) {
        Optional<UUID> existing = readValid(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID fresh = UUID.randomUUID();
        response.addHeader("Set-Cookie", buildSetCookieHeader(fresh, request.isSecure()));
        return fresh;
    }

    private Optional<UUID> parseAndVerify(String cookieValue) {
        int dot = cookieValue == null ? -1 : cookieValue.indexOf('.');
        if (dot <= 0) return Optional.empty();
        String uuidPart = cookieValue.substring(0, dot);
        String signaturePart = cookieValue.substring(dot + 1);
        try {
            String expectedSignature = sign(uuidPart);
            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signaturePart.getBytes(StandardCharsets.UTF_8))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(uuidPart));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar la cookie de sesión anónima", e);
        }
    }

    /** Escrito a mano (en vez de ResponseCookie) porque necesitamos SameSite=Lax explícito
     * junto con HttpOnly, y Set-Cookie va por header crudo para no depender de la versión de
     * Servlet API disponible para esos atributos combinados. "Secure" solo se agrega si el
     * propio request llegó por HTTPS: en dev local (http://localhost) todo es texto plano, y
     * un navegador nunca reenvía una cookie Secure sobre una conexión no seguro — forzarla
     * ahí rompería el flujo de invitados en dev sin ganar nada (localhost no es un origen
     * expuesto a red). */
    private String buildSetCookieHeader(UUID sessionId, boolean secureRequest) {
        String value = sessionId + "." + sign(sessionId.toString());
        String header = COOKIE_NAME + "=" + value
                + "; Max-Age=" + COOKIE_TTL.toSeconds()
                + "; Path=/; HttpOnly; SameSite=Lax";
        return secureRequest ? header + "; Secure" : header;
    }
}
