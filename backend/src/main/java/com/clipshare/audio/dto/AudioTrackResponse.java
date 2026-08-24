package com.clipshare.audio.dto;

import com.clipshare.audio.AudioTrack;

import java.util.UUID;

public record AudioTrackResponse(UUID id, String title, int durationMs, String audioUrl, String sourceUrl) {
    public static AudioTrackResponse from(AudioTrack track) {
        return new AudioTrackResponse(track.getId(), track.getTitle(), track.getDurationMs(),
                "/media/audio/" + track.getFilePath().substring("audio/".length()), track.getSourceUrl());
    }
}
