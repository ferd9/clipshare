package com.clipshare.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Cliente HTTP compartido para los endpoints públicos de oEmbed (YouTube/Vimeo/TikTok, ver
 * docs/SPEC.md sección 11.10) — todos son un simple GET que devuelve JSON, sin credenciales. */
@Component
class OembedHttpClient {

    private static final Logger log = LoggerFactory.getLogger(OembedHttpClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    Optional<JsonNode> fetch(String oembedUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(oembedUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("No se pudo resolver el oEmbed {}", oembedUrl, e);
            return Optional.empty();
        }
    }
}
