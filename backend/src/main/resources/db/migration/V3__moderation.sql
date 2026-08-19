CREATE TYPE moderation_check_type AS ENUM ('CSAM_HASH', 'MANUAL_REVIEW', 'DMCA_TAKEDOWN', 'REINSTATEMENT');
CREATE TYPE moderation_result AS ENUM ('CLEAN', 'FLAGGED', 'REPORTED_NCMEC', 'APPROVED', 'REJECTED');

CREATE TABLE moderation_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clip_id UUID NOT NULL REFERENCES clips(id) ON DELETE CASCADE,
    check_type moderation_check_type NOT NULL,
    result moderation_result NOT NULL,
    reviewer_id UUID REFERENCES users(id),    -- NULL si el check fue automático (ej. hash-matching)
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_moderation_logs_clip ON moderation_logs(clip_id);

-- tabla de evidencia para un hallazgo de CSAM: guarda SOLO metadata del match y el id del reporte
-- devuelto por NCMEC, nunca el contenido en sí — esto es lo que sustenta el reporte legal sin
-- convertir tu propia base de datos en un repositorio adicional del material
CREATE TABLE csam_hash_matches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clip_id UUID NOT NULL REFERENCES clips(id),
    frame_timestamp_ms INTEGER,
    matched_hash_source VARCHAR(50),          -- ej. 'NCMEC_PDQ'
    ncmec_report_id VARCHAR(100),             -- referencia devuelta por la CyberTipline API al reportar
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE strike_reason AS ENUM ('DMCA_CONFIRMED', 'CSAM_CONFIRMED', 'HARASSMENT', 'OTHER');

CREATE TABLE strikes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    reason strike_reason NOT NULL,
    report_id UUID,                            -- FK a reports(id), ver V4 (se agrega ahí por orden de creación)
    severity INTEGER NOT NULL DEFAULT 1,        -- CSAM_CONFIRMED = severidad alta (ban inmediato, no espera 3 strikes)
    expires_at TIMESTAMPTZ,                     -- strikes de copyright pueden prescribir (ej. 12 meses); CSAM no
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_strikes_user ON strikes(user_id);
