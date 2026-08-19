package com.clipshare.comment.dto;

import com.clipshare.comment.Comment;
import com.clipshare.comment.CommentAuthorType;
import com.clipshare.comment.CommentStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminCommentSummary(
        UUID id,
        UUID clipId,
        CommentAuthorType authorType,
        String authorDisplayName,
        String body,
        CommentStatus status,
        int reportCount,
        Instant createdAt
) {
    public static AdminCommentSummary from(Comment comment) {
        String authorName = comment.getAuthorType() == CommentAuthorType.USER
                ? comment.getUser().getDisplayName()
                : comment.getGuestDisplayName();
        return new AdminCommentSummary(
                comment.getId(),
                comment.getClip().getId(),
                comment.getAuthorType(),
                authorName,
                comment.getBody(),
                comment.getStatus(),
                comment.getReportCount(),
                comment.getCreatedAt());
    }
}
