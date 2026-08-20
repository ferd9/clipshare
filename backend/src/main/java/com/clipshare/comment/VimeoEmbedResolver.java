package com.clipshare.comment;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

@Component
class VimeoEmbedResolver implements VideoEmbedResolver {

    private final OembedHttpClient oembedHttpClient;

    VimeoEmbedResolver(OembedHttpClient oembedHttpClient) {
        this.oembedHttpClient = oembedHttpClient;
    }

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.VIMEO;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("vimeo.com")) {
            return Optional.empty();
        }
        String path = uri.getPath();
        String externalId = path == null ? null : Arrays.stream(path.split("/"))
                .filter(s -> !s.isBlank())
                .findFirst()
                .filter(s -> s.matches("\\d+"))
                .orElse(null);
        if (externalId == null) {
            return Optional.empty();
        }

        String oembedUrl = "https://vimeo.com/api/oembed.json?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
        Optional<JsonNode> json = oembedHttpClient.fetch(oembedUrl);
        String title = json.map(n -> n.path("title").asText(null)).orElse(null);
        String thumbnail = json.map(n -> n.path("thumbnail_url").asText(null)).orElse(null);

        return Optional.of(new EmbedResolution(EmbedPlatform.VIMEO, externalId, title, thumbnail, true));
    }
}
