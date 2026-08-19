package com.clipshare.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Los tokens opacos (verificación de email, reset de password, refresh token) nunca se
 * persisten en texto plano: solo su hash. Así, un dump de la base de datos no permite
 * suplantar sesiones ni flujos de verificación/reset.
 */
@Component
public class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Genera un token opaco aleatorio y seguro, en formato URL-safe, para enviar al cliente/email. */
    public String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
