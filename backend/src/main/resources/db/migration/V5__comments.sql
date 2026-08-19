-- Fase 6 (docs/SPEC.md sección 11): comentarios abiertos a cualquiera (con o sin sesión),
-- con controles anti-abuso más estrictos para invitados. El spec la llama V6__comments.sql
-- asumiendo migraciones 1:1 con cada fase; acá la Fase 5 (pulido) no necesitó tocar el
-- schema, así que este archivo sigue siendo V5 para no dejar un hueco en la numeración.
CREATE TYPE comment_author_type AS ENUM ('USER', 'GUEST');
CREATE TYPE comment_status AS ENUM ('VISIBLE', 'PENDING_REVIEW', 'HIDDEN', 'REMOVED');
CREATE TYPE report_target_type AS ENUM ('CLIP', 'COMMENT');

CREATE TABLE comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clip_id UUID NOT NULL REFERENCES clips(id) ON DELETE CASCADE,
    parent_comment_id UUID REFERENCES comments(id),   -- hilos de respuesta, opcional

    author_type comment_author_type NOT NULL,
    user_id UUID REFERENCES users(id),                -- NULL si author_type = GUEST
    guest_display_name VARCHAR(50),                    -- generado por el sistema (ej. "Invitado #4821"),
                                                         -- nunca un campo libre: evita suplantar nombres de otros usuarios

    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 500),
    status comment_status NOT NULL DEFAULT 'VISIBLE',

    ip_hash VARCHAR(64) NOT NULL,          -- SHA-256(IP + salt diario) — nunca IP en texto plano
    anon_session_id UUID,                  -- cookie firmada de larga duración, ver AnonSessionService
                                            -- (también se guarda para USER, útil en investigaciones)
    content_hash VARCHAR(64) NOT NULL,     -- SHA-256 del cuerpo normalizado (trim + lowercase), detecta flood de texto idéntico

    like_count INTEGER NOT NULL DEFAULT 0,
    report_count INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_comment_author CHECK (
        (author_type = 'USER' AND user_id IS NOT NULL) OR
        (author_type = 'GUEST' AND user_id IS NULL AND guest_display_name IS NOT NULL)
    )
);

CREATE INDEX idx_comments_clip ON comments(clip_id, created_at DESC)
    WHERE deleted_at IS NULL AND status = 'VISIBLE';
CREATE INDEX idx_comments_ip_hash ON comments(ip_hash, created_at);
CREATE INDEX idx_comments_anon_session ON comments(anon_session_id, created_at);
CREATE INDEX idx_comments_content_hash ON comments(content_hash, created_at);

-- persiste bloqueos de origen entre reinicios de Redis (Redis maneja el rate-limit en caliente;
-- esta tabla es la fuente de verdad durable para shadow-bans activos)
CREATE TABLE blocked_origins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ip_hash VARCHAR(64),
    anon_session_id UUID,
    reason TEXT,
    blocked_until TIMESTAMPTZ,      -- NULL = indefinido, requiere revisión manual
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_blocked_origins_ip ON blocked_origins(ip_hash);
CREATE INDEX idx_blocked_origins_anon ON blocked_origins(anon_session_id);

-- el sistema de reportes (V4) se generaliza para cubrir comentarios además de clips
ALTER TABLE reports ADD COLUMN target_type report_target_type NOT NULL DEFAULT 'CLIP';
ALTER TABLE reports ALTER COLUMN clip_id DROP NOT NULL;
ALTER TABLE reports ADD COLUMN comment_id UUID REFERENCES comments(id);
ALTER TABLE reports ADD CONSTRAINT chk_report_target CHECK (
    (target_type = 'CLIP' AND clip_id IS NOT NULL AND comment_id IS NULL) OR
    (target_type = 'COMMENT' AND comment_id IS NOT NULL AND clip_id IS NULL)
);
