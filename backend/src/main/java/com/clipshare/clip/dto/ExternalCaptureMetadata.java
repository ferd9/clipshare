package com.clipshare.clip.dto;

import com.clipshare.clip.ClipPlatform;

/**
 * Metadata de la captura client-side que viaja junto al blob en POST /api/clips/from-capture
 * (docs/SPEC.md sección 9, Caso B). {@code sourceClipStartMs}/{@code sourceClipEndMs} son
 * puramente informativos (a qué tramo del video original corresponde, para mostrar "vía
 * YouTube" — nunca se usan para volver a descargar nada). {@code trimStartMs}/
 * {@code trimEndMs} son distintos: recortan el propio archivo subido (offsets 0-based dentro
 * de la GRABACIÓN, elegidos en el editor con la vista de filmstrip) — {@code trimEndMs} nulo
 * significa "hasta el final de la grabación".
 */
public record ExternalCaptureMetadata(
        String sourceUrl,
        ClipPlatform sourcePlatform,
        String sourceExternalId,
        int sourceClipStartMs,
        int sourceClipEndMs,
        String sourceTitle,
        int trimStartMs,
        Integer trimEndMs
) {
}
