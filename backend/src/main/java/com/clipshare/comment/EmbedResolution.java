package com.clipshare.comment;

/**
 * Resultado de resolver una URL contra las plataformas de video reconocidas (docs/SPEC.md
 * sección 11.10). {@code platform} no nulo significa "la URL pertenece a una plataforma
 * reconocida" incluso si {@code embeddable} es false (caso Facebook/Instagram, stub sin
 * credenciales — igual se etiqueta la plataforma para que activar la integración real sea
 * solo cambiar el resolver, no todo el flujo).
 */
public record EmbedResolution(EmbedPlatform platform, String externalId, String title, String thumbnailUrl, boolean embeddable) {
    public static EmbedResolution notRecognized() {
        return new EmbedResolution(null, null, null, null, false);
    }
}
