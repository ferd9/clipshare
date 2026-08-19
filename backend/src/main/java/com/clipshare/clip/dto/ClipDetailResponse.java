package com.clipshare.clip.dto;

import com.clipshare.clip.Clip;
import com.clipshare.clip.ModerationStatus;
import com.clipshare.clip.ProcessingStatus;

import java.time.Instant;
import java.util.UUID;

public record ClipDetailResponse(
        UUID id,
        UUID ownerId,
        String ownerDisplayName,
        ProcessingStatus processingStatus,
        ModerationStatus moderationStatus,
        Integer durationMs,
        Integer width,
        Integer height,
        long viewCount,
        long likeCount,
        Instant createdAt,
        Instant publishedAt,
        String videoUrl,
        String thumbnailUrl
) {
    public static ClipDetailResponse from(Clip clip) {
        // El video/thumbnail solo se sirve una vez publicado — antes de eso vive en una ruta
        // no expuesta por WebConfig (ver docs/SPEC.md sección 10: nada se muestra públicamente
        // sin pasar antes por moderación).
        boolean published = clip.getModerationStatus() == ModerationStatus.PUBLISHED;
        return new ClipDetailResponse(
                clip.getId(),
                clip.getOwner().getId(),
                clip.getOwner().getDisplayName(),
                clip.getProcessingStatus(),
                clip.getModerationStatus(),
                clip.getDurationMs(),
                clip.getWidth(),
                clip.getHeight(),
                clip.getViewCount(),
                clip.getLikeCount(),
                clip.getCreatedAt(),
                clip.getPublishedAt(),
                published ? "/media/clips/" + clip.getId() + "/final.mp4" : null,
                published ? "/media/clips/" + clip.getId() + "/thumb.jpg" : null
        );
    }
}
