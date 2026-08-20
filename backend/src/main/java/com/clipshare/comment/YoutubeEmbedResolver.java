package com.clipshare.comment;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

@Component
class YoutubeEmbedResolver implements VideoEmbedResolver {

    private final OembedHttpClient oembedHttpClient;

    YoutubeEmbedResolver(OembedHttpClient oembedHttpClient) {
        this.oembedHttpClient = oembedHttpClient;
    }

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.YOUTUBE;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("youtube.com") && !normalizedHost.equals("youtu.be")) {
            return Optional.empty();
        }

        String externalId = extractId(uri, normalizedHost);
        if (externalId == null) {
            return Optional.empty();
        }

        // oEmbed es solo metadata decorativa (título/miniatura) — react-player embebe con el
        // id igual aunque esta llamada falle, así que embeddable queda true de todas formas.
        String oembedUrl = "https://www.youtube.com/oembed?format=json&url="
                + URLEncoder.encode(url, StandardCharsets.UTF_8);
        Optional<JsonNode> json = oembedHttpClient.fetch(oembedUrl);
        String title = json.map(n -> n.path("title").asText(null)).orElse(null);
        String thumbnail = json.map(n -> n.path("thumbnail_url").asText(null)).orElse(null);

        return Optional.of(new EmbedResolution(EmbedPlatform.YOUTUBE, externalId, title, thumbnail, true));
    }

    private String extractId(URI uri, String host) {
        if (host.equals("youtu.be")) {
            String path = uri.getPath();
            return path != null && path.length() > 1 ? path.substring(1) : null;
        }
        String query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2 && parts[0].equals("v")) {
                    return parts[1];
                }
            }
        }
        // /shorts/{id} y /embed/{id}
        String path = uri.getPath();
        if (path != null) {
            var segments = Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toList();
            if (segments.size() >= 2 && (segments.get(0).equals("shorts") || segments.get(0).equals("embed"))) {
                return segments.get(1);
            }
        }
        return null;
    }
}
