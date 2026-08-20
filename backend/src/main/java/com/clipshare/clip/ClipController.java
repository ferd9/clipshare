package com.clipshare.clip;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.clip.dto.ClipDetailResponse;
import com.clipshare.clip.dto.ClipUploadResponse;
import com.clipshare.clip.dto.ExternalCaptureMetadata;
import com.clipshare.common.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/clips")
public class ClipController {

    private final ClipService clipService;

    public ClipController(ClipService clipService) {
        this.clipService = clipService;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClipUploadResponse upload(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        Clip clip = clipService.uploadOwnClip(principal.getUser(), file);
        return ClipUploadResponse.from(clip);
    }

    @PostMapping(value = "/from-capture", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClipUploadResponse fromCapture(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceUrl") String sourceUrl,
            @RequestParam("sourcePlatform") ClipPlatform sourcePlatform,
            @RequestParam(value = "sourceExternalId", required = false) String sourceExternalId,
            @RequestParam("sourceClipStartMs") int sourceClipStartMs,
            @RequestParam("sourceClipEndMs") int sourceClipEndMs,
            @RequestParam(value = "sourceTitle", required = false) String sourceTitle,
            @RequestParam(value = "trimStartMs", defaultValue = "0") int trimStartMs,
            @RequestParam(value = "trimEndMs", required = false) Integer trimEndMs) {
        var metadata = new ExternalCaptureMetadata(
                sourceUrl, sourcePlatform, sourceExternalId, sourceClipStartMs, sourceClipEndMs, sourceTitle,
                trimStartMs, trimEndMs);
        Clip clip = clipService.uploadExternalCapture(principal.getUser(), file, metadata);
        return ClipUploadResponse.from(clip);
    }

    @GetMapping("/feed")
    public PageResponse<ClipDetailResponse> feed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Clip> result = clipService.getFeed(page, size);
        return PageResponse.from(result, ClipDetailResponse::from);
    }

    @GetMapping("/{id}")
    public ClipDetailResponse detail(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id) {
        var requester = principal == null ? null : principal.getUser();
        Clip clip = clipService.getVisibleClip(id, requester);
        return ClipDetailResponse.from(clip);
    }
}
