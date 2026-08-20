-- Fase 6b (docs/SPEC.md sección 11.9): adjuntos de comentario para usuarios autenticados —
-- imagen, referencia a otro clip, o enlace externo. Los GUEST solo pueden mandar body texto.
CREATE TYPE attachment_type AS ENUM ('IMAGE', 'CLIP_REFERENCE', 'LINK');
CREATE TYPE attachment_moderation_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED');

CREATE TABLE comment_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Nullable a propósito, a diferencia del spec original: una imagen se sube ANTES de que
    -- exista el comentario ("POST /api/comments/attachments/image" devuelve un attachmentId
    -- pendiente para referenciar al crear el comentario, ver sección 11.9) — con comment_id
    -- NOT NULL esa fila no podría insertarse todavía. Queda NULL mientras el adjunto está
    -- "pendiente de usar" y se completa recién cuando el comentario que lo referencia se crea.
    comment_id UUID REFERENCES comments(id) ON DELETE CASCADE,

    -- Agregado sobre el spec original: quién subió el adjunto, para poder verificar que
    -- solo su propio autor lo referencie al crear el comentario (sin esto, cualquiera que
    -- adivinara un attachmentId ajeno podría reusar la imagen de otro usuario).
    uploaded_by UUID NOT NULL REFERENCES users(id),

    attachment_type attachment_type NOT NULL,

    -- solo aplica si attachment_type = IMAGE
    image_path TEXT,
    image_content_hash VARCHAR(64),
    image_mime_type VARCHAR(50),

    -- solo aplica si attachment_type = CLIP_REFERENCE
    referenced_clip_id UUID REFERENCES clips(id),

    -- solo aplica si attachment_type = LINK
    link_url TEXT,
    link_domain VARCHAR(255),               -- extraído del URL al guardar, para bloqueo/alerta por dominio
    embed_platform VARCHAR(20),              -- YOUTUBE | VIMEO | TWITCH | TIKTOK | INSTAGRAM | FACEBOOK | NULL (no reconocido) — Fase 6c
    embed_external_id VARCHAR(150),          -- Fase 6c
    embed_title TEXT,                        -- Fase 6c
    embed_thumbnail_url TEXT,                -- Fase 6c
    is_embeddable BOOLEAN NOT NULL DEFAULT FALSE, -- Fase 6c

    moderation_status attachment_moderation_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- CommentAttachmentService verifica CSAM de forma síncrona ANTES de insertar (ver nota
    -- de deviation ahí): la fila de una IMAGE recién subida ya se persiste con image_path
    -- resuelto y moderation_status = APPROVED, nunca en un estado PENDING intermedio — así
    -- que este constraint puede seguir exigiendo image_path siempre, igual que el spec original.
    CONSTRAINT chk_attachment_payload CHECK (
        (attachment_type = 'IMAGE' AND image_path IS NOT NULL) OR
        (attachment_type = 'CLIP_REFERENCE' AND referenced_clip_id IS NOT NULL) OR
        (attachment_type = 'LINK' AND link_url IS NOT NULL)
    )
);
CREATE INDEX idx_comment_attachments_comment ON comment_attachments(comment_id);
CREATE INDEX idx_comment_attachments_link_domain ON comment_attachments(link_domain);
CREATE INDEX idx_comment_attachments_uploaded_by ON comment_attachments(uploaded_by);

-- lista de dominios conocidos como maliciosos/phishing/ilegales — alimentable manualmente
-- y/o desde una API externa de reputación (ver TODO en LinkSafetyService)
CREATE TABLE blocked_link_domains (
    domain VARCHAR(255) PRIMARY KEY,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- defensa en profundidad: aunque el backend ya valide "GUEST no puede adjuntar nada",
-- la base de datos lo rechaza también a nivel de trigger, para que un bug en la capa de
-- aplicación no se convierta en un bypass de la regla. Se dispara tanto al insertar un
-- adjunto ya con comment_id (CLIP_REFERENCE/LINK, creados junto con el comentario) como al
-- actualizar comment_id más tarde (IMAGE, que se "adopta" después de subida — ver arriba).
CREATE OR REPLACE FUNCTION prevent_guest_attachments() RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT author_type FROM comments WHERE id = NEW.comment_id) = 'GUEST' THEN
        RAISE EXCEPTION 'Los comentarios de invitados no admiten adjuntos';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_guest_attachments
    BEFORE INSERT OR UPDATE OF comment_id ON comment_attachments
    FOR EACH ROW
    WHEN (NEW.comment_id IS NOT NULL)
    EXECUTE FUNCTION prevent_guest_attachments();
