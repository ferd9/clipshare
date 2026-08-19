package com.clipshare.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

// TODO: integrar la CyberTipline API real de NCMEC tras el registro como ESP (docs/SPEC.md
// secciones 2 y 10). Con MockCsamHashService activo (default) esto nunca se llama en dev,
// porque el mock no reporta matches — queda igual implementado y conectado en el pipeline
// para que activar la integración real sea solo cambiar esta clase, no tocar el resto.
@Component
public class StubNcmecReportClient implements NcmecReportClient {

    private static final Logger log = LoggerFactory.getLogger(StubNcmecReportClient.class);

    @Override
    public String report(UUID clipId, String matchedHashSource) {
        log.warn("[STUB] Reporte a NCMEC CyberTipline NO enviado (integración pendiente) — clip={} hashSource={}",
                clipId, matchedHashSource);
        return null;
    }
}
