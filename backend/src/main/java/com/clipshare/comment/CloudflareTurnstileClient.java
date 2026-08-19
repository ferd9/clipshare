package com.clipshare.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Verificación real contra la API de Cloudflare Turnstile (docs/SPEC.md sección 3). Se
 * activa con {@code APP_TURNSTILE_PROVIDER=cloudflare} una vez que haya una secret key real
 * (hoy el placeholder de dev es {@code change-me-in-dev}, ver .env.example) — hasta entonces
 * el bean activo por defecto es {@link MockTurnstileClient}.
 */
@Component
@ConditionalOnProperty(name = "app.turnstile.provider", havingValue = "cloudflare")
public class CloudflareTurnstileClient implements TurnstileClient {

    private static final Logger log = LoggerFactory.getLogger(CloudflareTurnstileClient.class);
    private static final URI SITEVERIFY_URL = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    private final String secretKey;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CloudflareTurnstileClient(@Value("${app.turnstile.secret-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public boolean verify(String token, String remoteIp) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String form = "secret=" + secretKey + "&response=" + token
                    + (remoteIp != null ? "&remoteip=" + remoteIp : "");
            HttpRequest request = HttpRequest.newBuilder(SITEVERIFY_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            return body.path("success").asBoolean(false);
        } catch (Exception e) {
            log.warn("No se pudo verificar el token de Turnstile contra Cloudflare", e);
            return false; // si la API de Cloudflare falla, no se abre la puerta: se rechaza el comentario
        }
    }
}
