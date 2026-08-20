package com.clipshare.comment;

import com.clipshare.comment.dto.LinkPreviewResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** UX auxiliar (docs/SPEC.md sección 11.10): resuelve un link en caliente mientras el usuario
 * escribe el comentario, para mostrar la vista previa antes de publicar. Requiere sesión
 * (cubierto por el catch-all `anyRequest().authenticated()` de SecurityConfig — no hace falta
 * una regla explícita). */
@RestController
public class LinkPreviewController {

    private final VideoEmbedResolverService resolverService;

    public LinkPreviewController(VideoEmbedResolverService resolverService) {
        this.resolverService = resolverService;
    }

    @GetMapping("/api/link-preview")
    public LinkPreviewResponse preview(@RequestParam String url) {
        return LinkPreviewResponse.from(resolverService.resolve(url));
    }
}
