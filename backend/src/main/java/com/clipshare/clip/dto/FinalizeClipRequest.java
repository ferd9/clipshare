package com.clipshare.clip.dto;

import java.util.UUID;

public record FinalizeClipRequest(int trimStartMs, int trimEndMs, boolean muteOriginalAudio,
                                   UUID replacementAudioTrackId, Integer replacementAudioStartMs,
                                   Integer replacementAudioEndMs, String title,
                                   Double originalAudioVolume, Double replacementAudioVolume) {
}
