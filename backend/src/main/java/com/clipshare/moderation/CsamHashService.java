package com.clipshare.moderation;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Hash-matching perceptual de un frame contra listas de hashes conocidos de CSAM (PDQ, ver
 * docs/SPEC.md secciones 3 y 10). Interfaz reemplazable a propósito: acceder a la lista real
 * de hashes requiere estar registrado como ESP ante NCMEC — un trámite administrativo, no
 * técnico (sección 2), pendiente antes de producción. {@link MockCsamHashService} es la
 * implementación de desarrollo local; {@link NcmecCsamHashService} queda como stub hasta que
 * ese trámite esté resuelto.
 */
public interface CsamHashService {

    record FrameCheckResult(boolean matched, String hashHex, String matchedHashSource) {
        public static FrameCheckResult clean() {
            return new FrameCheckResult(false, null, null);
        }
    }

    FrameCheckResult checkFrame(Path frameImagePath) throws IOException;
}
