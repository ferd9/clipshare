package com.clipshare.comment;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

/** Twitch no tiene oEmbed público (docs/SPEC.md sección 11.10) — el iframe se arma
 * directamente con el id extraído del URL, sin llamada HTTP ni credenciales. Sin metadata
 * (título/miniatura) por el mismo motivo: no hay de dónde pedirla sin una API con credenciales. */
@Component
class TwitchEmbedResolver implements VideoEmbedResolver {

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.TWITCH;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("twitch.tv") && !normalizedHost.equals("clips.twitch.tv")) {
            return Optional.empty();
        }
        String path = uri.getPath();
        String externalId = path == null ? null : Arrays.stream(path.split("/"))
                .filter(s -> !s.isBlank())
                .reduce((first, second) -> second) // último segmento, igual criterio que platformDetection.ts
                .orElse(null);
        if (externalId == null) {
            return Optional.empty();
        }
        return Optional.of(new EmbedResolution(EmbedPlatform.TWITCH, externalId, null, null, true));
    }
}
