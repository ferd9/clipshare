package com.clipshare.clip;

import com.clipshare.auth.AppUserPrincipal;
import com.clipshare.clip.dto.ClipDetailResponse;
import com.clipshare.clip.dto.ClipUploadResponse;
import com.clipshare.clip.dto.FinalizeClipRequest;
import com.clipshare.clip.dto.ImportClipRequest;
import com.clipshare.common.dto.PageResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
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

    /** Reemplaza al viejo POST /from-capture (grabación de pantalla, retirado por calidad
     * inaceptable — ver docs/SPEC.md): server-side download vía yt-dlp, mismo criterio de
     * riesgo de ToS ya aceptado explícitamente para este proyecto. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClipUploadResponse importFromLink(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestBody ImportClipRequest request) {
        Clip clip = clipService.importFromLink(principal.getUser(), request.sourceUrl(), request.sourcePlatform());
        return ClipUploadResponse.from(clip);
    }

    /** Archivo "editable" (fase AWAITING_EDIT) que muestra el editor de recorte — solo el
     * dueño puede verlo, nunca se expone vía /media/** como el contenido ya publicado. */
    @GetMapping("/{id}/editable")
    public ResponseEntity<FileSystemResource> editable(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id) {
        Path path = clipService.getEditableFilePath(id, principal.getUser());
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("video/mp4"))
                .body(new FileSystemResource(path));
    }

    @PostMapping("/{id}/finalize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ClipUploadResponse finalize(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody FinalizeClipRequest request) {
        var finalizeRequest = new ClipService.FinalizeRequest(
                request.trimStartMs(), request.trimEndMs(), request.muteOriginalAudio(), request.replacementAudioTrackId(),
                request.replacementAudioStartMs(), request.replacementAudioEndMs(), request.title(),
                request.originalAudioVolume(), request.replacementAudioVolume());
        Clip clip = clipService.finalizeClip(id, principal.getUser(), finalizeRequest);
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
