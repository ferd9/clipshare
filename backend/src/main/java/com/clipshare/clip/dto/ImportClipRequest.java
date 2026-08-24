package com.clipshare.clip.dto;

import com.clipshare.clip.ClipPlatform;

public record ImportClipRequest(String sourceUrl, ClipPlatform sourcePlatform) {
}
