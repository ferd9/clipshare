package com.clipshare.comment;

/**
 * Valida el token de Cloudflare Turnstile que manda el frontend en cada comentario de
 * invitado (docs/SPEC.md sección 11.4). Interfaz reemplazable — mismo patrón que
 * {@code CsamHashService}: {@link MockTurnstileClient} es la implementación de dev/local
 * (no hay cuenta de Cloudflare real en este entorno), {@link CloudflareTurnstileClient} es
 * la real, activable con {@code APP_TURNSTILE_PROVIDER=cloudflare} + una secret key real.
 */
public interface TurnstileClient {
    boolean verify(String token, String remoteIp);
}
