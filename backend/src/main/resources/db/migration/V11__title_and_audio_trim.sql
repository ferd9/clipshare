-- Título opcional, elegido recién al finalizar (nunca obligatorio) — ver ClipService.finalizeClip.
ALTER TABLE clips ADD COLUMN title TEXT;

-- Desde qué punto (ms) del audio_track_id elegido arrancar la pista de reemplazo. Vive acá y
-- no en audio_tracks: un mismo "sonido" (audio_track) se puede reusar en más de un clip (ver
-- audio_tracks.usage_count, pensado para eso), cada uno con su propio fragmento elegido — el
-- offset es propiedad de ESTE uso del audio, no de la pista en sí. El final del fragmento no
-- se guarda por separado: ya queda acotado por la duración final del clip (source_clip_end_ms
-- - source_clip_start_ms) vía el -t de salida en FfmpegProcessor.finalizeClip.
ALTER TABLE clips ADD COLUMN replacement_audio_start_ms INTEGER;
