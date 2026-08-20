package com.clipshare.comment.dto;

import com.clipshare.comment.Comment;
import com.clipshare.comment.CommentAttachment;
import com.clipshare.comment.CommentAuthorType;
import com.clipshare.user.User;
import com.clipshare.user.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID clipId,
        UUID parentCommentId,
        CommentAuthorType authorType,
        String authorDisplayName,
        String body,
        int likeCount,
        Instant createdAt,
        boolean canDelete,
        List<AttachmentResponse> attachments
) {
    /** @param viewer quien está mirando (null si es un visitante sin sesión) — determina si
     * el frontend debe mostrar el botón de borrar (dueño o ADMIN/MODERATOR, ver
     * CommentService.deleteComment).
     * @param attachments ya filtrados/cargados por el caller (ver CommentController) — evita
     * que este DTO dispare una query por comentario. */
    public static CommentResponse from(Comment comment, User viewer, List<CommentAttachment> attachments) {
        String authorName = comment.getAuthorType() == CommentAuthorType.USER
                ? comment.getUser().getDisplayName()
                : comment.getGuestDisplayName();
        boolean isOwner = viewer != null && comment.getUser() != null && comment.getUser().getId().equals(viewer.getId());
        boolean isModerator = viewer != null && (viewer.getRole() == UserRole.ADMIN || viewer.getRole() == UserRole.MODERATOR);
        return new CommentResponse(
                comment.getId(),
                comment.getClip().getId(),
                comment.getParentComment() != null ? comment.getParentComment().getId() : null,
                comment.getAuthorType(),
                authorName,
                comment.getBody(),
                comment.getLikeCount(),
                comment.getCreatedAt(),
                isOwner || isModerator,
                attachments.stream().map(AttachmentResponse::from).toList());
    }
}
