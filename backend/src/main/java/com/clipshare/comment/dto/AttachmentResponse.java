package com.clipshare.comment.dto;

import com.clipshare.comment.AttachmentType;
import com.clipshare.comment.CommentAttachment;

import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        AttachmentType type,
        String imageUrl,
        UUID referencedClipId,
        String linkUrl,
        String linkDomain
) {
    public static AttachmentResponse from(CommentAttachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getAttachmentType(),
                attachment.getImagePath() != null ? "/media/" + attachment.getImagePath() : null,
                attachment.getReferencedClip() != null ? attachment.getReferencedClip().getId() : null,
                attachment.getLinkUrl(),
                attachment.getLinkDomain());
    }
}
