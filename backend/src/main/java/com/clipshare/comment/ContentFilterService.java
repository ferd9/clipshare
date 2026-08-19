package com.clipshare.comment;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filtro automático de contenido, síncrono (docs/SPEC.md sección 11.5) — distinto del
 * hash-matching de CSAM en video, que es asíncrono y corre en el worker. Acá todo es texto,
 * así que corre en el mismo request que crea el comentario.
 *
 * La lista de patrones prohibidos es deliberadamente chica y solo cubre spam/phishing obvio:
 * el español tiene demasiadas variantes regionales para que un filtro rígido de palabras no
 * termine marcando mal comentarios legítimos (ver nota del spec), así que un match acá NUNCA
 * bloquea directamente — solo manda el comentario a PENDING_REVIEW para que lo vea un
 * moderador humano.
 */
@Component
public class ContentFilterService {

    private static final int FLOOD_THRESHOLD_ORIGINS = 5;
    private static final Duration FLOOD_WINDOW = Duration.ofMinutes(10);

    // Starter list a propósito chica — spam/phishing genérico, no intenta ser un filtro de
    // lenguaje ofensivo (eso es un proyecto en sí mismo y con mucho más riesgo de falsos
    // positivos). Ampliable sin tocar el resto del pipeline.
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(gan[aá] dinero|hazte rico|dinero r[aá]pido)\\b"),
            Pattern.compile("(?i)\\b(compra seguidores|comprar? seguidores|likes? gratis)\\b"),
            Pattern.compile("(?i)\\b(haz clic aqu[ií]|click aqu[ií])\\b"),
            Pattern.compile("(?i)\\bonlyfans\\b.{0,20}\\bgratis\\b"),
            Pattern.compile("(?i)\\bverifica tu cuenta\\b.{0,30}\\b(link|enlace)\\b")
    );

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\bhttps?://\\S+|\\bwww\\.\\S+");

    private final CommentRepository commentRepository;
    private final ShadowBanService shadowBanService;

    public ContentFilterService(CommentRepository commentRepository, ShadowBanService shadowBanService) {
        this.commentRepository = commentRepository;
        this.shadowBanService = shadowBanService;
    }

    /**
     * @param trustedUser cuenta autenticada con >7 días y sin strikes (mismo criterio que
     *                     CommentRateLimitService) — a esas cuentas se les permite un link
     *                     libremente; a cualquier otra (invitado, cuenta nueva, con strikes)
     *                     un link manda el comentario a revisión.
     */
    public CommentStatus decideInitialStatus(CommentAuthorType authorType, boolean trustedUser, String body) {
        if (containsForbiddenPattern(body)) {
            return CommentStatus.PENDING_REVIEW;
        }
        boolean hasUrl = URL_PATTERN.matcher(body).find();
        boolean urlAllowedFreely = authorType == CommentAuthorType.USER && trustedUser;
        if (hasUrl && !urlAllowedFreely) {
            return CommentStatus.PENDING_REVIEW;
        }
        return CommentStatus.VISIBLE;
    }

    public boolean containsForbiddenPattern(String body) {
        return FORBIDDEN_PATTERNS.stream().anyMatch(p -> p.matcher(body).find());
    }

    /**
     * Flood de texto idéntico desde distintos orígenes (patrón de bot coordinado): si el
     * mismo {@code content_hash} (cuerpo normalizado) aparece desde {@value
     * #FLOOD_THRESHOLD_ORIGINS} o más {@code ip_hash} distintos en los últimos {@value
     * #FLOOD_WINDOW} minutos, se banea automáticamente cada uno de esos orígenes.
     *
     * @return true si esta llamada detectó y aplicó un ban por flood (incluye el origen actual).
     */
    public boolean checkAndBanFloodOrigins(String contentHash, String currentIpHash) {
        Instant since = Instant.now().minus(FLOOD_WINDOW);
        List<String> priorIpHashes = commentRepository.findDistinctIpHashesWithContentHashSince(contentHash, since);
        Set<String> distinctOrigins = new HashSet<>(priorIpHashes);
        distinctOrigins.add(currentIpHash);

        if (distinctOrigins.size() < FLOOD_THRESHOLD_ORIGINS) {
            return false;
        }
        for (String ipHash : distinctOrigins) {
            shadowBanService.banIndefinitely(ipHash, null, "duplicate_flood");
        }
        return true;
    }
}
