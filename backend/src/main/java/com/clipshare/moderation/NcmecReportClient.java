package com.clipshare.moderation;

import java.util.UUID;

/**
 * Reporte obligatorio a la CyberTipline de NCMEC ante cualquier hallazgo positivo de CSAM
 * (18 U.S.C. §2258A, ver docs/SPEC.md secciones 2 y 10). Reemplazable: la integración real
 * requiere las mismas credenciales de membresía ESP que {@link CsamHashService}.
 */
public interface NcmecReportClient {

    /** @return el id de reporte devuelto por la CyberTipline API, para guardar en csam_hash_matches. */
    String report(UUID clipId, String matchedHashSource);
}
