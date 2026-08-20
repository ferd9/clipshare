package com.clipshare.comment;

import com.clipshare.clip.Clip;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Adjunto de un comentario (docs/SPEC.md sección 11.9): imagen, referencia a otro clip, o
 * enlace externo — solo para autores {@code USER} (nunca GUEST, reforzado también por el
 * trigger {@code trg_prevent_guest_attachments} a nivel de base de datos). {@code comment} es
 * nullable a propósito: una IMAGE se sube y se modera ANTES de que el comentario exista (ver
 * CommentAttachmentService) y recién se "adopta" (se completa {@code comment}) cuando el
 * comentario que la referencia se crea.
 */
@Entity
@Table(name = "comment_attachments")
public class CommentAttachment {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id")
    private Comment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "attachment_type", nullable = false)
    private AttachmentType attachmentType;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "image_content_hash")
    private String imageContentHash;

    @Column(name = "image_mime_type")
    private String imageMimeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referenced_clip_id")
    private Clip referencedClip;

    @Column(name = "link_url")
    private String linkUrl;

    @Column(name = "link_domain")
    private String linkDomain;

    // embed_* (Fase 6c, docs/SPEC.md sección 11.10) — solo aplica si attachmentType = LINK.
    @Enumerated(EnumType.STRING)
    @Column(name = "embed_platform", length = 20)
    private EmbedPlatform embedPlatform;

    @Column(name = "embed_external_id")
    private String embedExternalId;

    @Column(name = "embed_title")
    private String embedTitle;

    @Column(name = "embed_thumbnail_url")
    private String embedThumbnailUrl;

    @Column(name = "is_embeddable", nullable = false)
    private boolean embeddable = false;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "moderation_status", nullable = false)
    private AttachmentModerationStatus moderationStatus = AttachmentModerationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommentAttachment() {
        // JPA
    }

    /** Adjunto de imagen ya moderado y listo (docs/SPEC.md sección 11.9) — la verificación
     * CSAM corre de forma síncrona ANTES de esta llamada (ver CommentAttachmentService), así
     * que la fila se crea completa de una vez, nunca en un estado PENDING intermedio a medio
     * llenar (evita pisar {@code chk_attachment_payload}, que exige {@code image_path} no nulo
     * para el tipo IMAGE — Hibernate/Postgres asignan el {@code id} recién al insertar, así
     * que {@code imagePath} usa un token de storage propio, generado aparte, no ese id). Sin
     * comentario todavía: se adopta con {@link #attachToComment}. */
    public static CommentAttachment approvedImage(User uploadedBy, String imagePath, String imageContentHash, String imageMimeType) {
        CommentAttachment attachment = new CommentAttachment();
        attachment.uploadedBy = uploadedBy;
        attachment.attachmentType = AttachmentType.IMAGE;
        attachment.imagePath = imagePath;
        attachment.imageContentHash = imageContentHash;
        attachment.imageMimeType = imageMimeType;
        attachment.moderationStatus = AttachmentModerationStatus.APPROVED;
        return attachment;
    }

    public static CommentAttachment forClipReference(Comment comment, User uploadedBy, Clip referencedClip) {
        CommentAttachment attachment = new CommentAttachment();
        attachment.comment = comment;
        attachment.uploadedBy = uploadedBy;
        attachment.attachmentType = AttachmentType.CLIP_REFERENCE;
        attachment.referencedClip = referencedClip;
        attachment.moderationStatus = AttachmentModerationStatus.APPROVED; // ya pasó moderación al publicarse
        return attachment;
    }

    /** @param embed resultado de VideoEmbedResolverService — null o "no reconocida" deja el
     * link como un enlace simple (sin embed), igual que cualquier otro dominio no reconocido. */
    public static CommentAttachment forLink(Comment comment, User uploadedBy, String linkUrl, String linkDomain,
                                             EmbedResolution embed) {
        CommentAttachment attachment = new CommentAttachment();
        attachment.comment = comment;
        attachment.uploadedBy = uploadedBy;
        attachment.attachmentType = AttachmentType.LINK;
        attachment.linkUrl = linkUrl;
        attachment.linkDomain = linkDomain;
        if (embed != null && embed.platform() != null) {
            attachment.embedPlatform = embed.platform();
            attachment.embedExternalId = embed.externalId();
            attachment.embedTitle = embed.title();
            attachment.embedThumbnailUrl = embed.thumbnailUrl();
            attachment.embeddable = embed.embeddable();
        }
        // El link en sí no pasa por un pipeline de moderación asíncrono (a diferencia de la
        // imagen) — el chequeo de dominio bloqueado es síncrono y afecta el status del
        // comentario, no el del adjunto (ver CommentService/LinkSafetyService).
        attachment.moderationStatus = AttachmentModerationStatus.APPROVED;
        return attachment;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void attachToComment(Comment comment) {
        this.comment = comment;
    }

    public UUID getId() {
        return id;
    }

    public Comment getComment() {
        return comment;
    }

    public User getUploadedBy() {
        return uploadedBy;
    }

    public AttachmentType getAttachmentType() {
        return attachmentType;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getImageContentHash() {
        return imageContentHash;
    }

    public String getImageMimeType() {
        return imageMimeType;
    }

    public Clip getReferencedClip() {
        return referencedClip;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public String getLinkDomain() {
        return linkDomain;
    }

    public EmbedPlatform getEmbedPlatform() {
        return embedPlatform;
    }

    public String getEmbedExternalId() {
        return embedExternalId;
    }

    public String getEmbedTitle() {
        return embedTitle;
    }

    public String getEmbedThumbnailUrl() {
        return embedThumbnailUrl;
    }

    public boolean isEmbeddable() {
        return embeddable;
    }

    public AttachmentModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
