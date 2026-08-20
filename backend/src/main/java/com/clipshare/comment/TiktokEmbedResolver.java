package com.clipshare.comment;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

@Component
class TiktokEmbedResolver implements VideoEmbedResolver {

    private final OembedHttpClient oembedHttpClient;

    TiktokEmbedResolver(OembedHttpClient oembedHttpClient) {
        this.oembedHttpClient = oembedHttpClient;
    }

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.TIKTOK;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("tiktok.com") && !normalizedHost.equals("vm.tiktok.com")) {
            return Optional.empty();
        }

        String oembedUrl = "https://www.tiktok.com/oembed?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        Optional<JsonNode> json = oembedHttpClient.fetch(oembedUrl);

        // embed_product_id es el id real que necesita el iframe de embed — más confiable que
        // parsear el path (las URLs de TikTok tienen formas variadas, incluidos links cortos
        // vm.tiktok.com que no traen el id en la URL en absoluto). Sin respuesta de oEmbed
        // (host no reconocido por TikTok, link roto, o vm.tiktok.com sin resolver el redirect)
        // no hay id disponible: no se puede embeber, pero la plataforma igual se etiqueta.
        String externalId = json.map(n -> n.path("embed_product_id").asText(null))
                .filter(id -> id != null && !id.isBlank())
                .orElseGet(() -> extractIdFromPath(uri));
        String title = json.map(n -> n.path("title").asText(null)).orElse(null);
        String thumbnail = json.map(n -> n.path("thumbnail_url").asText(null)).orElse(null);
        boolean embeddable = externalId != null;

        return Optional.of(new EmbedResolution(EmbedPlatform.TIKTOK, externalId, title, thumbnail, embeddable));
    }

    private String extractIdFromPath(URI uri) {
        String path = uri.getPath();
        if (path == null) return null;
        return Arrays.stream(path.split("/"))
                .filter(s -> s.matches("\\d+"))
                .reduce((first, second) -> second)
                .orElse(null);
    }
}
