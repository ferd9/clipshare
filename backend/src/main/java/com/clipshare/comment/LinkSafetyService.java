package com.clipshare.comment;

/**
 * Chequeo dinámico de reputación de un dominio enlazado en un comentario (docs/SPEC.md
 * sección 11.9), complementario a la lista estática {@code blocked_link_domains}. Interfaz
 * reemplazable — mismo patrón que {@code CsamHashService}.
 */
public interface LinkSafetyService {

    record SafetyResult(boolean safe, String reason) {
        public static SafetyResult ok() {
            return new SafetyResult(true, null);
        }
    }

    SafetyResult checkDomain(String domain);
}
