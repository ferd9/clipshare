package com.clipshare.clip.dto;

import com.clipshare.clip.ClipPlatform;

/**
 * Metadata de la captura client-side que viaja junto al blob en POST /api/clips/from-capture
 * (docs/SPEC.md sección 9, Caso B). Puramente informativa/evidencia — nunca se usa para
 * volver a descargar nada del video fuente.
 */
public record ExternalCaptureMetadata(
        String sourceUrl,
        ClipPlatform sourcePlatform,
        String sourceExternalId,
        int sourceClipStartMs,
        int sourceClipEndMs,
        String sourceTitle
) {
}
