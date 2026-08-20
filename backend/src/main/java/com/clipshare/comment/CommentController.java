package com.clipshare.comment;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.comment.dto.CommentResponse;
import com.clipshare.comment.dto.CreateCommentReportRequest;
import com.clipshare.comment.dto.CreateCommentRequest;
import com.clipshare.common.dto.PageResponse;
import com.clipshare.report.Report;
import com.clipshare.report.ReportService;
import com.clipshare.report.dto.ReportResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class CommentController {

    private final CommentService commentService;
    private final CommentAttachmentRepository attachmentRepository;
    private final ReportService reportService;
    private final AnonSessionService anonSessionService;
    private final IpHashService ipHashService;

    public CommentController(CommentService commentService, CommentAttachmentRepository attachmentRepository,
                              ReportService reportService, AnonSessionService anonSessionService,
                              IpHashService ipHashService) {
        this.commentService = commentService;
        this.attachmentRepository = attachmentRepository;
        this.reportService = reportService;
        this.anonSessionService = anonSessionService;
        this.ipHashService = ipHashService;
    }

    @GetMapping("/api/clips/{id}/comments")
    public PageResponse<CommentResponse> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") UUID clipId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request, HttpServletResponse response) {
        UUID anonSessionId = anonSessionService.ensureSession(request, response);
        var viewer = principal != null ? principal.getUser() : null;
        UUID viewerUserId = viewer != null ? viewer.getId() : null;
        Page<Comment> result = commentService.listComments(clipId, page, size, viewerUserId, anonSessionId);

        List<UUID> commentIds = result.getContent().stream().map(Comment::getId).toList();
        Map<UUID, List<CommentAttachment>> attachmentsByComment = attachmentRepository.findByCommentIdIn(commentIds)
                .stream().collect(Collectors.groupingBy(a -> a.getComment().getId()));

        return PageResponse.from(result, comment -> CommentResponse.from(
                comment, viewer, attachmentsByComment.getOrDefault(comment.getId(), List.of())));
    }

    @PostMapping("/api/clips/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable("id") UUID clipId,
            @Valid @RequestBody CreateCommentRequest request,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        UUID anonSessionId = anonSessionService.ensureSession(servletRequest, servletResponse);
        String remoteIp = ipHashService.clientIp(servletRequest);
        String ipHash = ipHashService.hash(remoteIp);
        Comment comment = commentService.createComment(clipId, principal, request, ipHash, anonSessionId, remoteIp);
        List<CommentAttachment> attachments = attachmentRepository.findByCommentId(comment.getId());
        return CommentResponse.from(comment, principal != null ? principal.getUser() : null, attachments);
    }

    @PostMapping("/api/comments/{id}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse report(@PathVariable("id") UUID commentId, @Valid @RequestBody CreateCommentReportRequest request) {
        Report report = reportService.createCommentReport(commentId, request);
        return ReportResponse.from(report);
    }

    @DeleteMapping("/api/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable("id") UUID commentId) {
        commentService.deleteComment(commentId, principal.getUser());
    }
}
