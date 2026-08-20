package com.clipshare.comment;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resuelve una URL de comentario contra las plataformas de video reconocidas (docs/SPEC.md
 * sección 11.10), síncrono — es solo una llamada rápida a un endpoint de oEmbed, no una
 * cola. Usado tanto al crear un comentario (attachment LINK estructurado o URL suelta en el
 * body, ver CommentService) como por GET /api/link-preview.
 */
@Service
public class VideoEmbedResolverService {

    private final List<VideoEmbedResolver> resolvers;

    public VideoEmbedResolverService(List<VideoEmbedResolver> resolvers) {
        this.resolvers = resolvers;
    }

    public EmbedResolution resolve(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return EmbedResolution.notRecognized();
        }
        String host = normalizeHost(uri.getHost());
        if (host == null) {
            return EmbedResolution.notRecognized();
        }
        for (VideoEmbedResolver resolver : resolvers) {
            Optional<EmbedResolution> result = resolver.tryResolve(url, uri, host);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return EmbedResolution.notRecognized();
    }

    private String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }
}
