package com.clipshare.comment;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.clip.Clip;
import com.clipshare.clip.ClipRepository;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.comment.dto.AttachmentRequest;
import com.clipshare.comment.dto.CreateCommentRequest;
import com.clipshare.config.ApiException;
import com.clipshare.user.User;
import com.clipshare.user.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
    private final CommentAttachmentRepository attachmentRepository;
    private final CommentAttachmentService attachmentService;
    private final BlockedLinkDomainRepository blockedLinkDomainRepository;
    private final LinkSafetyService linkSafetyService;
    private final CommentRateLimitService rateLimitService;
    private final ContentFilterService contentFilterService;
    private final ShadowBanService shadowBanService;
    private final TurnstileClient turnstileClient;

    public CommentService(CommentRepository commentRepository, ClipRepository clipRepository,
                           CommentAttachmentRepository attachmentRepository, CommentAttachmentService attachmentService,
                           BlockedLinkDomainRepository blockedLinkDomainRepository, LinkSafetyService linkSafetyService,
                           CommentRateLimitService rateLimitService, ContentFilterService contentFilterService,
                           ShadowBanService shadowBanService, TurnstileClient turnstileClient) {
        this.commentRepository = commentRepository;
        this.clipRepository = clipRepository;
        this.attachmentRepository = attachmentRepository;
        this.attachmentService = attachmentService;
        this.blockedLinkDomainRepository = blockedLinkDomainRepository;
        this.linkSafetyService = linkSafetyService;
        this.rateLimitService = rateLimitService;
        this.contentFilterService = contentFilterService;
        this.shadowBanService = shadowBanService;
        this.turnstileClient = turnstileClient;
    }

    /** Resultado de validar un AttachmentRequest, ya resuelto a las entidades reales — separado
     * de la persistencia porque la validación corre ANTES de que el Comment exista (para
     * fallar rápido sin gastar el cupo de rate-limit en un adjunto inválido). */
    private record ResolvedAttachment(AttachmentType type, CommentAttachment pendingImage, Clip referencedClip,
                                       String linkUrl, String linkDomain) {
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

        // Los GUEST solo pueden mandar body texto (docs/SPEC.md sección 11.9) — chequeo
        // temprano, antes de gastar turnstile/rate-limit en una request que se va a rechazar.
        if (authorType == CommentAuthorType.GUEST && !request.attachments().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GUEST_ATTACHMENTS_NOT_ALLOWED",
                    "Los comentarios de invitados no admiten adjuntos");
        }

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

        // Validar los adjuntos ANTES de gastar el cupo de rate-limit — que un adjunto
        // inválido (imagen ajena, clip no publicado, URL rota) falle rápido y gratis.
        List<ResolvedAttachment> resolvedAttachments = validateAttachments(user, request.attachments());
        boolean linkDomainBlocked = resolvedAttachments.stream()
                .filter(a -> a.type() == AttachmentType.LINK)
                .anyMatch(a -> isLinkDomainBlocked(a.linkDomain()));

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
            CommentStatus filterStatus = contentFilterService.decideInitialStatus(authorType, trustedUser, request.body());
            status = (filterStatus == CommentStatus.PENDING_REVIEW || linkDomainBlocked)
                    ? CommentStatus.PENDING_REVIEW : filterStatus;
        }

        Comment comment = new Comment(clip, parent, authorType, user, guestDisplayName, request.body(),
                ipHash, anonSessionId, contentHash);
        comment.setStatus(status);
        Comment saved = commentRepository.save(comment);
        persistAttachments(saved, user, resolvedAttachments);

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

    private List<ResolvedAttachment> validateAttachments(User user, List<AttachmentRequest> specs) {
        List<ResolvedAttachment> resolved = new ArrayList<>();
        for (AttachmentRequest spec : specs) {
            switch (spec.type()) {
                case IMAGE -> {
                    if (spec.attachmentId() == null) {
                        throw ApiException.badRequest("MISSING_ATTACHMENT_ID", "Falta el id del adjunto de imagen");
                    }
                    CommentAttachment pending = attachmentService.resolvePendingImage(spec.attachmentId(), user);
                    resolved.add(new ResolvedAttachment(AttachmentType.IMAGE, pending, null, null, null));
                }
                case CLIP_REFERENCE -> {
                    if (spec.referencedClipId() == null) {
                        throw ApiException.badRequest("MISSING_REFERENCED_CLIP_ID", "Falta el clip a referenciar");
                    }
                    Clip referenced = clipRepository.findById(spec.referencedClipId())
                            .filter(c -> !c.isDeleted() && c.getModerationStatus() == ModerationStatus.PUBLISHED)
                            .orElseThrow(() -> ApiException.badRequest("REFERENCED_CLIP_NOT_FOUND",
                                    "El clip referenciado no existe o no está publicado"));
                    resolved.add(new ResolvedAttachment(AttachmentType.CLIP_REFERENCE, null, referenced, null, null));
                }
                case LINK -> {
                    String domain = extractDomain(spec.linkUrl());
                    resolved.add(new ResolvedAttachment(AttachmentType.LINK, null, null, spec.linkUrl(), domain));
                }
            }
        }
        return resolved;
    }

    private void persistAttachments(Comment comment, User user, List<ResolvedAttachment> resolved) {
        for (ResolvedAttachment r : resolved) {
            switch (r.type()) {
                case IMAGE -> {
                    r.pendingImage().attachToComment(comment);
                    attachmentRepository.save(r.pendingImage());
                }
                case CLIP_REFERENCE ->
                        attachmentRepository.save(CommentAttachment.forClipReference(comment, user, r.referencedClip()));
                case LINK -> attachmentRepository.save(CommentAttachment.forLink(comment, user, r.linkUrl(), r.linkDomain()));
            }
        }
    }

    /** No se re-aloja ni se descarga el destino (docs/SPEC.md sección 11.9) — solo se valida
     * que sea una URL http(s) bien formada y se extrae el dominio para el chequeo de bloqueo. */
    private String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.badRequest("MISSING_LINK_URL", "Falta la URL del enlace");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) || host == null) {
                throw ApiException.badRequest("INVALID_LINK_URL", "El enlace debe ser una URL http(s) válida");
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("INVALID_LINK_URL", "El enlace debe ser una URL http(s) válida");
        }
    }

    private boolean isLinkDomainBlocked(String domain) {
        if (blockedLinkDomainRepository.existsById(domain)) {
            return true;
        }
        return !linkSafetyService.checkDomain(domain).safe();
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
