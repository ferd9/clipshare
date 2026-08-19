package com.clipshare.comment;

import com.clipshare.comment.dto.AdminCommentSummary;
import com.clipshare.common.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Protegido por rol en SecurityConfig (/api/admin/** exige ADMIN o MODERATOR). */
@RestController
@RequestMapping("/api/admin/comments")
public class AdminCommentController {

    private final CommentService commentService;

    public AdminCommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/pending")
    public PageResponse<AdminCommentSummary> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Comment> result = commentService.getPendingComments(page, size);
        return PageResponse.from(result, AdminCommentSummary::from);
    }
}
