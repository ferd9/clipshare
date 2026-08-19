CREATE TYPE clip_source_type AS ENUM ('OWN_UPLOAD', 'EXTERNAL_CAPTURE');
CREATE TYPE clip_platform AS ENUM ('YOUTUBE', 'VIMEO', 'TWITCH', 'NONE');
CREATE TYPE processing_status AS ENUM ('QUEUED', 'PROCESSING', 'READY', 'FAILED');
CREATE TYPE moderation_status AS ENUM ('PENDING', 'PUBLISHED', 'REJECTED', 'TAKEN_DOWN');
CREATE TYPE clip_visibility AS ENUM ('PUBLIC', 'UNLISTED', 'PRIVATE');

-- pistas de audio también son contenido con copyright potencial (ej. canciones) y deben moderarse
-- igual que un clip de video, no asumir que "solo audio" es de menor riesgo legal
CREATE TABLE audio_tracks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    uploaded_by UUID REFERENCES users(id),
    title VARCHAR(255),
    file_path TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,        -- SHA-256, para dedupe y para no re-moderar el mismo audio cada vez
    moderation_status moderation_status NOT NULL DEFAULT 'PENDING',
    usage_count INTEGER NOT NULL DEFAULT 0,   -- cuántos clips la usan, útil para detectar "sonidos" populares tipo TikTok
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_audio_tracks_hash ON audio_tracks(content_hash);

CREATE TABLE clips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    source_type clip_source_type NOT NULL,

    -- metadata de la captura externa: puramente informativa/evidencia para disputas de autoría,
    -- JAMÁS se usa para volver a descargar el video fuente
    source_platform clip_platform NOT NULL DEFAULT 'NONE',
    source_url TEXT,
    source_external_id VARCHAR(100),
    source_clip_start_ms INTEGER,
    source_clip_end_ms INTEGER,
    source_title TEXT,                        -- obtenido vía oEmbed público, para trazar al creador original

    file_path TEXT,
    thumbnail_path TEXT,
    mime_type VARCHAR(50),
    file_size_bytes BIGINT,
    content_hash VARCHAR(64),                 -- SHA-256 del archivo final: dedupe + bloquear reintentos tras un takedown
    width INTEGER,
    height INTEGER,
    -- Nota de implementación (difiere del DDL original de docs/SPEC.md sección 7): sin NOT NULL.
    -- Para OWN_UPLOAD, la fila se inserta con processing_status = QUEUED antes de que el worker
    -- de ffmpeg recorte/normalice el archivo — recién ahí se conoce la duración final. Mismo
    -- razonamiento que ya aplicaba a content_hash (tampoco NOT NULL): el valor real no existe
    -- hasta que termina el pipeline async. El CHECK se mantiene para cuando sí tiene valor.
    duration_ms INTEGER CHECK (duration_ms <= 20000),

    audio_track_id UUID REFERENCES audio_tracks(id),

    processing_status processing_status NOT NULL DEFAULT 'QUEUED',   -- ¿ya terminó ffmpeg?
    moderation_status moderation_status NOT NULL DEFAULT 'PENDING',  -- ¿pasó moderación?
    visibility clip_visibility NOT NULL DEFAULT 'PUBLIC',

    view_count BIGINT NOT NULL DEFAULT 0,
    like_count BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ                    -- soft delete: se retiene durante el periodo de contra-notificación DMCA
                                               -- (10-14 días hábiles), no se borra físicamente al instante
);

CREATE INDEX idx_clips_feed ON clips(published_at DESC)
    WHERE moderation_status = 'PUBLISHED' AND visibility = 'PUBLIC' AND deleted_at IS NULL;
CREATE INDEX idx_clips_owner ON clips(owner_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_clips_content_hash ON clips(content_hash) WHERE content_hash IS NOT NULL;
