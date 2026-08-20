package com.clipshare.comment;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.comment.dto.UploadAttachmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
public class CommentAttachmentController {

    private final CommentAttachmentService attachmentService;

    public CommentAttachmentController(CommentAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(value = "/api/comments/attachments/image", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadAttachmentResponse uploadImage(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        UUID attachmentId = attachmentService.uploadImage(principal.getUser(), file);
        return new UploadAttachmentResponse(attachmentId);
    }
}
