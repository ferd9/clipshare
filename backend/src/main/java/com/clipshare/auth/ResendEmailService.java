package com.clipshare.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envío real de emails transaccionales vía la API de Resend (docs/SPEC.md sección 16) — se
 * activa con APP_EMAIL_PROVIDER=resend una vez que haya una API key real; por defecto (sin
 * configurar) el bean activo sigue siendo {@link LoggingEmailService}, mismo patrón que
 * CloudflareTurnstileClient/NcmecCsamHashService (mock/real intercambiables por config).
 *
 * Sin un dominio propio verificado en Resend, el remitente "sandbox" (onboarding@resend.dev)
 * solo puede mandar a la casilla con la que se creó la cuenta de Resend — mandarle a
 * cualquier usuario real requiere verificar un dominio ahí, pendiente hasta tener uno (ver
 * decisión tomada al desplegar en la nube: por ahora seguimos sin dominio propio).
 */
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final URI RESEND_URL = URI.create("https://api.resend.com/emails");

    private final String apiKey;
    private final String fromAddress;
    private final String frontendUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResendEmailService(
            @Value("${app.email.resend-api-key}") String apiKey,
            @Value("${app.email.from}") String fromAddress,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationToken) {
        String link = frontendUrl + "/verify-email?token=" + verificationToken;
        String html = "<p>Confirmá tu cuenta de ClipShare haciendo click en el siguiente link:</p>"
                + "<p><a href=\"" + link + "\">" + link + "</a></p>"
                + "<p>Si no creaste esta cuenta, podés ignorar este mensaje.</p>";
        send(toEmail, "Confirmá tu cuenta de ClipShare", html);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        // El link apunta a una pantalla que todavía no existe en el frontend (queda pendiente
        // de otra vuelta) — se manda igual para no dejar el método a medias, pero por ahora
        // termina en un 404 si alguien lo abre.
        String link = frontendUrl + "/reset-password?token=" + resetToken;
        String html = "<p>Pediste restablecer tu contraseña de ClipShare:</p>"
                + "<p><a href=\"" + link + "\">" + link + "</a></p>"
                + "<p>Si no fuiste vos, podés ignorar este mensaje.</p>";
        send(toEmail, "Restablecer tu contraseña de ClipShare", html);
    }

    @Override
    public void sendTakedownNotice(String toEmail, UUID clipId, String reason) {
        String html = "<p>Tu clip (" + clipId + ") fue retirado por el siguiente motivo:</p>"
                + "<p>" + reason + "</p>";
        send(toEmail, "Uno de tus clips fue retirado", html);
    }

    private void send(String toEmail, String subject, String html) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", fromAddress);
            body.put("to", List.of(toEmail));
            body.put("subject", subject);
            body.put("html", html);

            HttpRequest request = HttpRequest.newBuilder(RESEND_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Resend devolvió {} al mandar a {}: {}", response.statusCode(), toEmail, response.body());
            }
        } catch (Exception e) {
            // No se propaga: un email que no sale no debería tumbar el flujo (registro, reset,
            // etc.) que lo disparó — mismo criterio que CloudflareTurnstileClient con fallos de red.
            log.warn("No se pudo enviar el email a {} vía Resend", toEmail, e);
        }
    }
}
