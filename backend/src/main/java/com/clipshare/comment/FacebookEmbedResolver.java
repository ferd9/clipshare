package com.clipshare.comment;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

// TODO: registrar app en Meta for Developers y solicitar acceso al oEmbed Read API —
// docs/SPEC.md sección 11.10. Hasta entonces, la plataforma se etiqueta igual (útil para
// activar la integración real sin tocar el resto del flujo) pero is_embeddable siempre da
// false — el comentario muestra el link normal, con su interstitial de advertencia al
// hacer click, como cualquier otro enlace no reconocido.
@Component
class FacebookEmbedResolver implements VideoEmbedResolver {

    @Override
    public EmbedPlatform platform() {
        return EmbedPlatform.FACEBOOK;
    }

    @Override
    public Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost) {
        if (!normalizedHost.equals("facebook.com") && !normalizedHost.equals("fb.watch")) {
            return Optional.empty();
        }
        return Optional.of(new EmbedResolution(EmbedPlatform.FACEBOOK, null, null, null, false));
    }
}
