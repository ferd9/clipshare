package com.clipshare.comment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementación de dev/local: no hay cuenta real de Cloudflare configurada (docs/SPEC.md
 * sección 13, TURNSTILE_SECRET_KEY=change-me-in-dev). A propósito NO intenta simular una
 * verificación real — solo exige que el frontend haya mandado *algún* token no vacío (ver
 * CommentService, que ya rechaza antes de llegar acá si el invitado no mandó ninguno). El
 * frontend, en modo dev sin site key real, manda un token fijo desde un botón "simular
 * verificación" en vez de cargar el widget real — ver TurnstileWidget.tsx.
 */
@Component
@ConditionalOnProperty(name = "app.turnstile.provider", havingValue = "mock", matchIfMissing = true)
public class MockTurnstileClient implements TurnstileClient {

    @Override
    public boolean verify(String token, String remoteIp) {
        return token != null && !token.isBlank();
    }
}
