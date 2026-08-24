-- V7 (Fase de grabación de pantalla, ahora retirada) agregó trim_start_ms/trim_end_ms para
-- distinguir "recorte de la grabación" de "recorte del video original". Con el video real ya
-- descargado (V8) no hay más que una sola línea de tiempo: se elimina esa distinción y se
-- repotencia source_clip_start_ms/source_clip_end_ms (V2, ya existía) para representar el
-- recorte final elegido en el editor — ahora también para OWN_UPLOAD, no solo EXTERNAL_CAPTURE.
ALTER TABLE clips DROP COLUMN trim_start_ms;
ALTER TABLE clips DROP COLUMN trim_end_ms;

-- Silenciar el audio original del clip. El reemplazo en sí usa audio_track_id (V2, ya
-- existía en el esquema pero nunca se había llegado a usar).
ALTER TABLE clips ADD COLUMN mute_original_audio BOOLEAN NOT NULL DEFAULT FALSE;

-- Antes un FAILED no daba ninguna pista de qué pasó — ahora se guarda un mensaje legible
-- (ej. "el video dura más de 10 minutos", "no se pudo descargar") para mostrarle al usuario.
ALTER TABLE clips ADD COLUMN processing_error TEXT;

-- De dónde salió la pista de reemplazo cuando vino de un link externo (mismo criterio de
-- riesgo ya aceptado para el video) — NULL para audio subido directamente por el usuario.
ALTER TABLE audio_tracks ADD COLUMN source_url TEXT;
