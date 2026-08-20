package com.clipshare.comment.dto;

import com.clipshare.comment.EmbedPlatform;
import com.clipshare.comment.EmbedResolution;

public record LinkPreviewResponse(
        EmbedPlatform platform,
        String externalId,
        String title,
        String thumbnailUrl,
        boolean embeddable
) {
    public static LinkPreviewResponse from(EmbedResolution resolution) {
        return new LinkPreviewResponse(resolution.platform(), resolution.externalId(),
                resolution.title(), resolution.thumbnailUrl(), resolution.embeddable());
    }
}
