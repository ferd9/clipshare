package com.clipshare.auth;

import java.util.UUID;

/**
 * Interfaz reemplazable: en dev local no hay proveedor SMTP configurado, así que la
 * implementación por defecto solo loguea el link. En producción (ver docs/SPEC.md
 * sección 16) se reemplaza por un cliente real de Resend/Mailgun sin tocar el resto
 * del flujo de auth — mismo patrón que CsamHashService/NcmecReportClient (sección 10).
 */
public interface EmailService {

    void sendVerificationEmail(String toEmail, String verificationToken);

    void sendPasswordResetEmail(String toEmail, String resetToken);

    /** Flujo de notice-and-takedown (docs/SPEC.md sección 2): notificación al usuario autor. */
    void sendTakedownNotice(String toEmail, UUID clipId, String reason);
}
