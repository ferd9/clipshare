CREATE TYPE user_role AS ENUM ('USER', 'MODERATOR', 'ADMIN');
CREATE TYPE user_status AS ENUM ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'BANNED', 'DELETED');

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role user_role NOT NULL DEFAULT 'USER',
    status user_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at TIMESTAMPTZ,
    strike_count INTEGER NOT NULL DEFAULT 0,          -- contador denormalizado, actualizado por trigger/servicio
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ                            -- soft delete: derecho al olvido / GDPR
);
CREATE INDEX idx_users_status ON users(status) WHERE deleted_at IS NULL;

-- verificación de email obligatoria antes de poder publicar (no solo registrarse)
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- necesario para poder revocar sesiones (logout real, "cerrar sesión en todos los dispositivos",
-- o revocación forzada si se banea una cuenta) — un JWT sin esto es imposible de invalidar antes de que expire
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,
    user_agent TEXT,
    ip_hash VARCHAR(64),               -- hash (no texto plano) de la IP, por minimización de datos/privacidad
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
