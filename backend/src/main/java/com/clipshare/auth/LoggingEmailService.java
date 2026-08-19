package com.clipshare.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

// TODO: reemplazar por un cliente real (Resend/Mailgun, ver docs/SPEC.md sección 16)
// antes de producción. Mientras tanto, esto deja el flujo completo funcional en dev/local.
@Service
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendVerificationEmail(String toEmail, String verificationToken) {
        log.info("[email-verificacion] destinatario={} token={}", toEmail, verificationToken);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("[email-reset-password] destinatario={} token={}", toEmail, resetToken);
    }

    @Override
    public void sendTakedownNotice(String toEmail, UUID clipId, String reason) {
        log.info("[email-retiro-contenido] destinatario={} clipId={} motivo={}", toEmail, clipId, reason);
    }
}
