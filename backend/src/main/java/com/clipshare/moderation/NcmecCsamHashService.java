package com.clipshare.moderation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

// TODO: integrar PDQ real + la lista de hashes de NCMEC tras registrar la cuenta como
// Electronic Service Provider (ESP) — ver docs/SPEC.md secciones 2 y 10. Se activa con
// APP_MODERATION_CSAM_PROVIDER=ncmec una vez conectadas las credenciales reales; hasta
// entonces queda deshabilitado (MockCsamHashService es el bean activo por defecto).
@Service
@ConditionalOnProperty(name = "app.moderation.csam-provider", havingValue = "ncmec")
public class NcmecCsamHashService implements CsamHashService {

    @Override
    public FrameCheckResult checkFrame(Path frameImagePath) {
        throw new UnsupportedOperationException(
                "Integración NCMEC pendiente de aprobación de membresía ESP — ver docs/SPEC.md sección 2");
    }
}
