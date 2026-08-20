package com.clipshare.comment.dto;

import com.clipshare.comment.AttachmentType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Union discriminada por {@code type} — el resto de los campos según cuál sea (validado en
 * CommentAttachmentService, no con anotaciones, porque los requeridos varían por tipo). */
public record AttachmentRequest(
        @NotNull AttachmentType type,
        UUID attachmentId,       // IMAGE: id devuelto por POST /api/comments/attachments/image
        UUID referencedClipId,   // CLIP_REFERENCE
        String linkUrl           // LINK
) {
}
