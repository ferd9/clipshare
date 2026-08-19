package com.clipshare.comment;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.comment.dto.CreateCommentRequest;
import com.clipshare.config.ApiException;
import com.clipshare.user.User;
import com.clipshare.user.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Orquesta la creación/listado/borrado de comentarios (docs/SPEC.md sección 11): valida el
 * clip, aplica CAPTCHA para invitados, rate-limit, filtro de contenido y shadow-ban, en ese
 * orden — cada paso puede rechazar el comentario antes de llegar a persistirlo.
 */
@Service
public class CommentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository commentRepository;
    private final ClipRepository clipRepository;
    private final CommentRateLimitService rateLimitService;
    private final ContentFilterService contentFilterService;
    private final ShadowBanService shadowBanService;
    private final TurnstileClient turnstileClient;

    public CommentService(CommentRepository commentRepository, ClipRepository clipRepository,
                           CommentRateLimitService rateLimitService, ContentFilterService contentFilterService,
                           ShadowBanService shadowBanService, TurnstileClient turnstileClient) {
        this.commentRepository = commentRepository;
        this.clipRepository = clipRepository;
        this.rateLimitService = rateLimitService;
        this.contentFilterService = contentFilterService;
        this.shadowBanService = shadowBanService;
        this.turnstileClient = turnstileClient;
    }

    public Page<Comment> listComments(UUID clipId, int page, int size, UUID viewerUserId, UUID viewerAnonSessionId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return commentRepository.findVisibleForViewer(clipId, viewerUserId, viewerAnonSessionId, pageable);
    }

    @Transactional
    public Comment createComment(UUID clipId, AppUserPrincipal principal, CreateCommentRequest request,
                                  String ipHash, UUID anonSessionId, String remoteIp) {
        Clip clip = clipRepository.findByIdWithOwner(clipId)
                .filter(c -> !c.isDeleted() && c.getModerationStatus() == ModerationStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("CLIP_NOT_FOUND", "Clip no encontrado"));

        Comment parent = null;
        if (request.parentCommentId() != null) {
            parent = commentRepository.findById(request.parentCommentId())
                    .filter(c -> c.getClip().getId().equals(clipId) && !c.isDeleted())
                    .orElseThrow(() -> ApiException.badRequest("PARENT_COMMENT_NOT_FOUND",
                            "El comentario al que respondés no existe"));
        }

        User user = principal != null ? principal.getUser() : null;
        CommentAuthorType authorType = user != null ? CommentAuthorType.USER : CommentAuthorType.GUEST;

        String guestDisplayName = null;
        if (authorType == CommentAuthorType.GUEST) {
            if (request.turnstileToken() == null || request.turnstileToken().isBlank()) {
                throw ApiException.badRequest("TURNSTILE_REQUIRED",
                        "Se requiere verificación anti-bot para comentar sin cuenta");
            }
            if (!turnstileClient.verify(request.turnstileToken(), remoteIp)) {
                throw ApiException.badRequest("INVALID_TURNSTILE_TOKEN", "La verificación anti-bot no es válida");
            }
            // Nunca un campo libre — evita suplantar el nombre de otro usuario (docs/SPEC.md
            // sección 11.1). Determinístico por sesión: el mismo invitado se ve consistente
            // entre comentarios distintos del mismo navegador.
            guestDisplayName = "Invitado #" + Math.floorMod(anonSessionId.hashCode(), 10000);
        }

        if (user != null) {
            rateLimitService.enforceForUser(user);
        } else {
            rateLimitService.enforceForGuest(ipHash, anonSessionId);
        }

        String contentHash = sha256(normalize(request.body()));
        boolean shadowBanned = shadowBanService.isBanned(ipHash, anonSessionId);

        CommentStatus status;
        if (shadowBanned) {
            status = CommentStatus.HIDDEN;
        } else {
            boolean trustedUser = user != null && rateLimitService.isTrusted(user);
            status = contentFilterService.decideInitialStatus(authorType, trustedUser, request.body());
        }

        Comment comment = new Comment(clip, parent, authorType, user, guestDisplayName, request.body(),
                ipHash, anonSessionId, contentHash);
        comment.setStatus(status);
        Comment saved = commentRepository.save(comment);

        if (!shadowBanned) {
            boolean floodBanned = contentFilterService.checkAndBanFloodOrigins(contentHash, ipHash);
            if (floodBanned) {
                saved.setStatus(CommentStatus.HIDDEN); // este mismo comentario cae bajo el ban recién aplicado
            } else if (status == CommentStatus.PENDING_REVIEW) {
                boolean justBanned = shadowBanService.recordRiskSignal(ipHash, anonSessionId, "content_filter_flag");
                if (justBanned) {
                    saved.setStatus(CommentStatus.HIDDEN);
                }
            }
        }

        return saved;
    }

    @Transactional
    public void deleteComment(UUID commentId, User requester) {
        Comment comment = commentRepository.findByIdWithUser(commentId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> ApiException.notFound("COMMENT_NOT_FOUND", "Comentario no encontrado"));

        boolean isOwner = comment.getAuthorType() == CommentAuthorType.USER
                && comment.getUser() != null && comment.getUser().getId().equals(requester.getId());
        boolean isModerator = requester.getRole() == UserRole.ADMIN || requester.getRole() == UserRole.MODERATOR;
        // Los GUEST no tienen sesión para pedir el borrado de su propio comentario — se
        // cubren solo por reporte + moderación (docs/SPEC.md sección 11.8).
        if (!isOwner && !isModerator) {
            throw ApiException.forbidden("NOT_COMMENT_OWNER", "No podés borrar este comentario");
        }
        comment.setDeletedAt(Instant.now());
    }

    public Page<Comment> getPendingComments(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return commentRepository.findPendingReview(pageable);
    }

    private String normalize(String body) {
        return body.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
