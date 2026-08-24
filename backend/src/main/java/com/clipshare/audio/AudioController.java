package com.clipshare.audio;

import com.clipshare.audio.dto.AudioTrackResponse;
import com.clipshare.audio.dto.ImportAudioLinkRequest;
import com.clipshare.auth.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Pista de audio de reemplazo para un clip (docs/SPEC.md sección 1) — ver AudioTrackService. */
@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final AudioTrackService audioTrackService;

    public AudioController(AudioTrackService audioTrackService) {
        this.audioTrackService = audioTrackService;
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioTrackResponse upload(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        AudioTrack track = audioTrackService.uploadFile(principal.getUser(), file);
        return AudioTrackResponse.from(track);
    }

    @PostMapping("/import-link")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioTrackResponse importLink(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestBody ImportAudioLinkRequest request) {
        AudioTrack track = audioTrackService.importFromLink(principal.getUser(), request.sourceUrl());
        return AudioTrackResponse.from(track);
    }
}
