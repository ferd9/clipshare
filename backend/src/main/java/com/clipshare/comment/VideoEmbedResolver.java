package com.clipshare.comment;

import java.net.URI;
import java.util.Optional;

/**
 * Un resolver por plataforma (docs/SPEC.md sección 11.10) — Spring inyecta todas las
 * implementaciones como {@code List<VideoEmbedResolver>} en {@link VideoEmbedResolverService},
 * que las prueba en orden hasta que una reconozca el host.
 */
public interface VideoEmbedResolver {

    EmbedPlatform platform();

    /** @param normalizedHost sin "www."/"m." iniciales (ver VideoEmbedResolverService).
     * @return vacío si esta URL no pertenece a esta plataforma. */
    Optional<EmbedResolution> tryResolve(String url, URI uri, String normalizedHost);
}
