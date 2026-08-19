package com.clipshare.moderation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * TODO: sin integración real de PDQ + lista de hashes NCMEC — pendiente de aprobación de
 * membresía ESP (docs/SPEC.md secciones 2 y 10), un trámite administrativo, no técnico. Esta
 * implementación siempre da "limpio": a propósito NO calcula ningún hash perceptual real ni
 * simula un algoritmo — sin una lista de hashes real contra la cual comparar, cualquier cálculo
 * acá sería solo apariencia de detección. Existe únicamente para poder ejercitar el resto del
 * pipeline (extracción de frames, moderation_logs, strikes) en dev/local.
 */
@Service
@ConditionalOnProperty(name = "app.moderation.csam-provider", havingValue = "mock", matchIfMissing = true)
public class MockCsamHashService implements CsamHashService {

    @Override
    public FrameCheckResult checkFrame(Path frameImagePath) {
        return FrameCheckResult.clean();
    }
}
