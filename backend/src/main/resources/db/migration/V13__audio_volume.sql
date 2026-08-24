-- Volumen elegido en el editor para cada pista al mezclar audio original + reemplazo (ver
-- FfmpegProcessor.finalizeClip, filtros "volume=" antes de amix) — sin esto amix mezclaba las
-- dos pistas siempre al mismo nivel, ignorando el control deslizante que el usuario mueve en
-- ClipTrimmer/AudioTrimmer. 1.0 = volumen original sin cambios (default = comportamiento previo).
ALTER TABLE clips ADD COLUMN original_audio_volume REAL NOT NULL DEFAULT 1.0;
ALTER TABLE clips ADD COLUMN replacement_audio_volume REAL NOT NULL DEFAULT 1.0;

ALTER TABLE clips ADD CONSTRAINT clips_original_audio_volume_check CHECK (original_audio_volume >= 0 AND original_audio_volume <= 1);
ALTER TABLE clips ADD CONSTRAINT clips_replacement_audio_volume_check CHECK (replacement_audio_volume >= 0 AND replacement_audio_volume <= 1);
