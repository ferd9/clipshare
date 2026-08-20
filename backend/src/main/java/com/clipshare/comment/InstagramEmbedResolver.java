package com.clipshare.comment;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

// TODO: registrar app en Meta for Developers y solicitar acceso al oEmbed Read API —
// docs/SPEC.md sección 11.10 (acceso restringido desde 2020, requiere aprobación de Meta).
// Mismo patrón que FacebookEmbedResolver: se etiqueta la plataforma, is_embeddable false.
@Component
class InstagramEmbedResolver implements VideoEmbedResolver {

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.INSTAGRAM;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("instagram.com")) {
            return Optional.empty();
        }
        return Optional.of(new EmbedResolution(EmbedPlatform.INSTAGRAM, null, null, null, false));
    }
}
