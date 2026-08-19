CREATE TYPE report_reason AS ENUM ('COPYRIGHT_DMCA', 'CSAM', 'HARASSMENT', 'OTHER');
CREATE TYPE report_status AS ENUM ('OPEN', 'UNDER_REVIEW', 'ACTIONED', 'DISMISSED');

-- Los campos marcados abajo no son "extra": son los elementos que 17 U.S.C. §512(c)(3) exige
-- para que un aviso de retiro cuente como un DMCA notice válido. Un formulario que solo pida
-- "email + descripción" no es legalmente suficiente para activar el proceso de safe harbor.
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clip_id UUID NOT NULL REFERENCES clips(id),
    reason report_reason NOT NULL,
    reporter_name VARCHAR(255),
    reporter_email VARCHAR(255) NOT NULL,
    reporter_address TEXT,                     -- requerido en un DMCA notice formal
    description TEXT,
    good_faith_statement BOOLEAN,              -- "creo de buena fe que el uso no está autorizado..."
    accuracy_statement BOOLEAN,                 -- declaración bajo pena de perjurio
    signature TEXT,                             -- firma electrónica (nombre completo tipeado alcanza)
    status report_status NOT NULL DEFAULT 'OPEN',
    resolved_by UUID REFERENCES users(id),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reports_clip ON reports(clip_id);
CREATE INDEX idx_reports_status ON reports(status) WHERE status != 'DISMISSED';

ALTER TABLE strikes ADD CONSTRAINT fk_strikes_report FOREIGN KEY (report_id) REFERENCES reports(id);

CREATE TABLE dmca_counter_notices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id UUID NOT NULL REFERENCES reports(id),
    submitted_by UUID NOT NULL REFERENCES users(id),
    statement TEXT NOT NULL,
    consent_to_jurisdiction BOOLEAN NOT NULL,
    signature TEXT NOT NULL,
    restore_eligible_at TIMESTAMPTZ,            -- now() + 10 días hábiles al momento de recibir la contra-notificación
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
