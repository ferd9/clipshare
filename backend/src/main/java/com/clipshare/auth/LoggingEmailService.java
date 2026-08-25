package com.clipshare.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

// logging | resend — mismo patrón que MockCsamHashService/MockTurnstileClient. Sin esta
// condición, este bean queda activo SIEMPRE (incompondicional) y choca con ResendEmailService
// en cuanto app.email.provider=resend — exactamente el bug que tumbó el arranque en la nube
// (Spring no puede elegir entre dos beans de EmailService), porque en local nunca se prueba
// con ese valor puesto y el problema queda invisible hasta desplegar.
@Service
@ConditionalOnProperty(name = "app.email.provider", havingValue = "logging", matchIfMissing = true)
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
