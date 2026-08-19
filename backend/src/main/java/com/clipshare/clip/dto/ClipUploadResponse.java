package com.clipshare.clip.dto;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ProcessingStatus;

import java.util.UUID;

public record ClipUploadResponse(UUID id, ProcessingStatus processingStatus) {
    public static ClipUploadResponse from(Clip clip) {
        return new ClipUploadResponse(clip.getId(), clip.getProcessingStatus());
    }
}
