# ClipShare — Especificación Técnica de Implementación

> Documento de referencia para que un agente (Claude Code) implemente el MVP de forma autónoma dentro de una sesión de desarrollo local con Docker. Renombra "ClipShare" por el nombre real del producto antes de iniciar.

---

## 1. Resumen del proyecto

Plataforma web donde usuarios **autenticados** (sin anonimato) pueden crear "clips" cortos (máx. 20 segundos) de dos formas:

1. **Subiendo su propio archivo de video**, que se procesa server-side con `ffmpeg`.
2. **Pegando el link de un video de una plataforma externa** (YouTube, Vimeo, Twitch) y recortando un fragmento **desde el navegador**, usando el reproductor oficial embebido + captura client-side (`MediaRecorder` + `Canvas`) — nunca descargando el video fuente en el servidor.

El clip final puede llevar una pista de audio superpuesta y se publica en un feed público.

**Fase actual:** entorno de desarrollo 100% local con Docker Compose. El despliegue a producción (Hetzner + Cloudflare R2 + Cloudflare CDN, ~$20–25/mes dentro de un presupuesto de $100/mes) es una fase posterior, no cubierta en el Sprint 0-6 de este documento, pero se detalla en la sección 16 para referencia futura.

---

## 2. Requisitos legales y de cumplimiento (no negociables)

Estos requisitos son parte del "Definition of Done" del producto, no un extra opcional:

- **Cuentas obligatorias.** No se permite publicar ni subir contenido sin haber iniciado sesión. Esto es lo que permite cumplir con la política de terminación de usuarios reincidentes exigida por el *safe harbor* de la DMCA (17 U.S.C. §512).
- **Agente DMCA designado.** Antes de producción, registrar un agente DMCA ante el U.S. Copyright Office y publicar sus datos de contacto en el sitio (footer + página `/legal/dmca`).
- **Flujo de notice-and-takedown.** Endpoint/formulario público para reportar contenido infractor, con retiro expedito y notificación al usuario autor.
- **Política de reincidentes.** A la 3ª infracción confirmada (copyright o CSAM), la cuenta se suspende automáticamente. Debe quedar registrado en auditoría (`moderation_log`).
- **Detección de CSAM antes de publicar (pre-moderación, no post-moderación).** Todo clip pasa por un hash-matching de frames contra listas de hashes conocidos **antes** de quedar visible públicamente. Ver sección 10.
- **Registro como Electronic Service Provider (ESP) ante NCMEC** para acceder a las listas de hashes — trámite administrativo, no técnico, pendiente antes de producción.
- **Reporte obligatorio a NCMEC CyberTipline** ante cualquier hallazgo positivo (18 U.S.C. §2258A) — automatizar el envío del reporte desde el pipeline de moderación.
- **ToS y Privacy Policy** publicadas, incluyendo la política de reincidentes y el proceso de takedown.

El agente que implemente este spec debe dejar **stubs/TODOs claramente marcados** en el código para las integraciones que requieren trámites externos (NCMEC, DMCA agent), pero debe implementar la lógica y los endpoints como si la integración ya existiera (mockeable/inyectable), de forma que conectar las credenciales reales sea trivial.

---

## 3. Stack tecnológico

| Capa | Tecnología |
|---|---|
| Frontend | React 18 + Vite + TypeScript |
| Reproducción embebida | `react-player` (YouTube/Vimeo/Twitch unificado) |
| Waveform de audio | `wavesurfer.js` |
| Backend | Java 21 + Spring Boot 4 (Web, Security, Data JPA, Validation) |
| Autenticación | Spring Security + JWT (email/password + OAuth2 Google opcional) |
| Base de datos | PostgreSQL 16 |
| Cola de jobs | Redis 7 (Spring Data Redis / colas simples) |
| Procesamiento de video | `ffmpeg` (invocado vía `ProcessBuilder` o `bramp/ffmpeg-cli-wrapper`) |
| Hashing perceptual (moderación) | PDQ (librería open-source, originada en Meta) |
| CAPTCHA (comentarios de invitados) | Cloudflare Turnstile |
| Storage de archivos (prod) | Cloudflare R2 (S3-compatible) — en local, filesystem montado o MinIO |
| Contenedores | Docker + Docker Compose |

---

## 4. Arquitectura general

```
┌─────────────┐      ┌──────────────────┐      ┌─────────────┐
│   React     │ ───► │  Spring Boot API  │ ───► │ PostgreSQL  │
│  (Vite dev  │      │   (REST + JWT)    │      └─────────────┘
│   server)   │      │                   │
└─────────────┘      │   ├─ Auth         │      ┌─────────────┐
      │               │   ├─ Clips        │ ───► │    Redis    │
      │ MediaRecorder  │   ├─ Reports      │      │  (cola de   │
      │ + Canvas       │   └─ Moderation   │      │   jobs)     │
      │ (captura       └────────┬─────────┘      └──────┬──────┘
      │  client-side            │                        │
      ▼                         ▼                        ▼
┌─────────────┐         ┌──────────────┐        ┌─────────────────┐
│  YouTube /   │         │   MinIO /    │        │  Worker (ffmpeg  │
│  Vimeo IFrame│         │  filesystem  │ ◄──────│  + PDQ hashing)  │
│  (solo para  │         │  local (prod:│        │                  │
│  reproducir, │         │  Cloudflare  │        └─────────────────┘
│  nunca se    │         │  R2)         │
│  descarga)   │         └──────────────┘
└─────────────┘
```

Puntos clave de diseño:

- El backend **nunca** descarga videos de plataformas externas. Solo recibe el blob ya recortado que el navegador generó con `MediaRecorder`.
- Todo clip (venga de upload propio o de captura client-side) pasa por el mismo pipeline de moderación antes de marcarse como `PUBLISHED`.
- El worker de ffmpeg/hashing corre desacoplado de la API vía cola en Redis, para no bloquear requests HTTP.

---

## 5. Estructura de carpetas

```
clipshare/
├── docker-compose.yml
├── docker-compose.override.yml        # overrides de desarrollo (hot reload, volumes)
├── .env.example
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/clipshare/
│       ├── ClipshareApplication.java
│       ├── config/                    # SecurityConfig, RedisConfig, StorageConfig
│       ├── auth/                      # AuthController, JwtService, UserDetailsService
│       ├── user/                      # User entity, UserRepository, UserController
│       ├── clip/                      # Clip entity, ClipController, ClipService
│       ├── moderation/                # ModerationLog, CsamHashService, NcmecReportClient (stub)
│       ├── report/                    # Report entity (DMCA/abuse, polimórfico clip/comment), ReportController
│       ├── comment/                   # Comment entity, CommentController, RateLimitService, ContentFilterService, TurnstileClient
│       ├── worker/                    # FfmpegProcessor, PdqHashWorker, QueueListener
│       └── storage/                   # StorageService (interfaz), S3StorageImpl, LocalStorageImpl
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/              # Flyway: V1__init.sql, V2__moderation.sql, ...
├── frontend/
│   ├── Dockerfile
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/                       # cliente HTTP (axios/fetch wrapper)
│       ├── auth/                      # LoginPage, RegisterPage, AuthContext
│       ├── clips/
│       │   ├── UploadOwnClip.tsx
│       │   ├── ImportFromLink.tsx     # embebe react-player + captura MediaRecorder
│       │   ├── ClipEditor.tsx         # trim UI + wavesurfer.js
│       │   ├── ClipFeed.tsx
│       │   └── ClipCard.tsx
│       ├── comments/
│       │   ├── CommentForm.tsx        # incluye widget de Cloudflare Turnstile si no hay sesión;
│       │   │                          # solo muestra selector de adjuntos (imagen/clip/link) si hay sesión
│       │   ├── CommentList.tsx
│       │   ├── ExternalLinkGuard.tsx  # intercepta clicks en links y muestra el interstitial de advertencia
│       │   ├── CommentLinkPreview.tsx # embed reproducible si el link es de una plataforma de video reconocida
│       │   └── ReportCommentButton.tsx
│       ├── legal/                     # DmcaPage, TermsPage, PrivacyPage, ReportForm
│       └── hooks/
│           ├── useCanvasRecorder.ts   # lógica reutilizable de captura client-side
│           └── useAnonSession.ts      # lee/asegura la cookie anon_session_id
└── docs/
    └── SPEC.md                        # este archivo
```

---

## 6. Entorno de desarrollo local (Docker Compose)

`docker-compose.yml`:

```yaml
version: "3.9"
services:
  frontend:
    build:
      context: ./frontend
    ports:
      - "5173:5173"
    volumes:
      - ./frontend:/app
      - /app/node_modules
    environment:
      - VITE_API_BASE_URL=http://localhost:8080
    command: npm run dev -- --host

  backend:
    build:
      context: ./backend
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/clipshare
      - SPRING_DATASOURCE_USERNAME=dev
      - SPRING_DATASOURCE_PASSWORD=dev
      - SPRING_REDIS_HOST=redis
      - STORAGE_MODE=local                 # local | s3
      - STORAGE_LOCAL_PATH=/data/clips
      - JWT_SECRET=change-me-in-dev
    volumes:
      - ./backend:/app
      - clip-storage:/data/clips
    depends_on:
      - postgres
      - redis

  worker:
    build:
      context: ./backend
      dockerfile: Dockerfile.worker      # misma base + ffmpeg instalado
    environment:
      - SPRING_PROFILES_ACTIVE=worker
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/clipshare
      - SPRING_DATASOURCE_USERNAME=dev
      - SPRING_DATASOURCE_PASSWORD=dev
      - SPRING_REDIS_HOST=redis
      - STORAGE_MODE=local
      - STORAGE_LOCAL_PATH=/data/clips
    volumes:
      - clip-storage:/data/clips
    depends_on:
      - postgres
      - redis

  postgres:
    image: postgres:16
    environment:
      - POSTGRES_DB=clipshare
      - POSTGRES_USER=dev
      - POSTGRES_PASSWORD=dev
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7
    ports:
      - "6379:6379"

volumes:
  pgdata:
  clip-storage:
```

`backend/Dockerfile.worker` debe partir de la misma imagen base que `Dockerfile` pero agregando:

```dockerfile
RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*
```

Comando de arranque para el agente: `docker compose up --build`. La API debe quedar en `http://localhost:8080`, el frontend en `http://localhost:5173`.

---

## 7. Modelo de datos (PostgreSQL, vía Flyway)

El esquema se divide en migraciones separadas por dominio para que el agente pueda implementarlas de forma incremental junto con cada fase (sección 14):

- `V1__users_auth.sql` — usuarios y todo lo relacionado a sesión/credenciales.
- `V2__clips_media.sql` — clips y pistas de audio.
- `V3__moderation.sql` — moderación, CSAM y strikes.
- `V4__legal_reports.sql` — reportes DMCA, contra-notificaciones.
- `V5__social.sql` — likes (opcional, fase 5).
- `V6__comments.sql` — comentarios, bloqueo de orígenes abusivos, y generalización de `reports` a comentarios (fase 6, ver sección 11).

### V1 — Usuarios y autenticación

```sql
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
```

### V2 — Clips y audio

```sql
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
    duration_ms INTEGER CHECK (duration_ms <= 20000),         -- sin NOT NULL, ver nota de implementación abajo

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
```

> Nota de escalabilidad (no bloqueante para el MVP): `view_count`/`like_count` en la misma fila que se lee para el feed genera contención de escritura bajo tráfico alto. Si el feed empieza a sentirse lento, mover esos contadores a Redis (o a una tabla `clip_stats` separada) y sincronizar de forma asíncrona.
>
> Nota de implementación (Fase 2): `duration_ms` se define sin `NOT NULL` a propósito, a diferencia del DDL original de este documento. Para `OWN_UPLOAD` la fila se inserta con `processing_status = QUEUED` *antes* de que el worker de ffmpeg recorte/normalice el archivo — recién ahí se conoce la duración final real. Mismo razonamiento que ya aplicaba a `content_hash` (tampoco `NOT NULL`): ese valor no existe hasta que termina el pipeline asíncrono. El `CHECK (duration_ms <= 20000)` se mantiene para cuando sí tiene valor.

### V3 — Moderación, CSAM y strikes

```sql
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
```

Regla de negocio en `ModerationService`: al 3er `strike` vigente (no expirado) con `severity = 1` para un mismo `user_id`, o inmediatamente ante cualquier strike con `reason = 'CSAM_CONFIRMED'`, actualizar `users.status = 'SUSPENDED'` (o `BANNED` en el caso de CSAM) de forma atómica junto con la inserción del strike.

### V4 — Reportes DMCA y contra-notificaciones

```sql
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
```

### V5 — Social (fase 5, opcional para el MVP)

```sql
CREATE TABLE likes (
    user_id UUID NOT NULL REFERENCES users(id),
    clip_id UUID NOT NULL REFERENCES clips(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, clip_id)
);
```

**Resumen de lo que se agregó respecto a la primera versión y por qué:**

- `refresh_tokens` — sin esto, un JWT robado o una cuenta baneada no se puede invalidar hasta que expire solo.
- `email_verification_tokens` / `password_reset_tokens` — flujos básicos de cuenta que faltaban por completo.
- Tipos `ENUM` en vez de `VARCHAR` con comentario — Postgres valida los valores a nivel de base de datos, no solo en el código Java.
- `content_hash` en `clips` y `audio_tracks` — permite deduplicar y, más importante, **bloquear el reintento de subir contenido ya confirmado como infractor o CSAM** tras un takedown.
- Separación de `processing_status` y `moderation_status` — son dos cosas distintas ("¿ya terminó ffmpeg?" vs. "¿pasó moderación?") que la versión anterior mezclaba en un solo campo.
- `csam_hash_matches` — evidencia auditable de un hallazgo sin almacenar el contenido en sí.
- Campos legales completos en `reports` — sin ellos no tienes un DMCA notice válido, solo un formulario de contacto.
- `dmca_counter_notices` — el proceso de disputa es obligatorio si quieres safe harbor real, no solo el retiro.
- `strikes.severity` y `expires_at` — CSAM no debería esperar "3 strikes" como el copyright, y los strikes de copyright deberían poder prescribir.
- Índices en las columnas que realmente se consultan (`idx_clips_feed`, `idx_reports_status`, etc.) — el feed público es la query más frecuente del sistema y antes no tenía ni un índice pensado para ella.
- `deleted_at` (soft delete) en `users` y `clips` — necesario tanto para el periodo de contra-notificación DMCA como para cumplir con solicitudes de borrado de datos (GDPR/CCPA) sin perder el rastro de auditoría.

---

## 8. Backend — especificación de API (REST)

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| POST | `/api/auth/register` | No | Crea cuenta (email + password), dispara email de verificación |
| POST | `/api/auth/verify-email` | No | Consume `email_verification_tokens`, marca `email_verified_at` |
| POST | `/api/auth/login` | No | Devuelve JWT de acceso + refresh token (persistido en `refresh_tokens`) |
| POST | `/api/auth/refresh` | No (requiere refresh token válido) | Rota el refresh token, emite nuevo JWT |
| POST | `/api/auth/logout` | Sí | Revoca el refresh token actual (`revoked_at`) |
| POST | `/api/auth/password-reset/request` | No | Genera `password_reset_tokens`, envía email |
| POST | `/api/auth/password-reset/confirm` | No | Consume el token, actualiza `password_hash` |
| GET | `/api/users/me` | Sí | Perfil del usuario autenticado |
| POST | `/api/clips/upload` | Sí (sin verificar: máx. 3/día) | Sube archivo propio (multipart) → encola job de moderación + procesamiento |
| POST | `/api/clips/from-capture` | Sí (sin verificar: máx. 3/día) | Recibe el blob ya recortado en el navegador (desde `MediaRecorder`) + metadata de `source_*` → encola moderación |
| GET | `/api/clips/feed` | No | Lista clips con `moderation_status = PUBLISHED` y `visibility = PUBLIC`, paginado |
| GET | `/api/clips/{id}` | No | Detalle de un clip publicado |
| DELETE | `/api/clips/{id}` | Sí (dueño o admin) | Soft delete (`deleted_at`), no borra el archivo hasta vencer el periodo de retención |
| POST | `/api/clips/{id}/like` | Sí | Inserta en `likes`, incrementa `like_count` |
| POST | `/api/reports` | No (público, con email de contacto) | Crea un reporte con los campos legales de un DMCA notice (sección 7, V4) |
| POST | `/api/reports/{id}/counter-notice` | Sí (dueño del clip) | Crea `dmca_counter_notices`, calcula `restore_eligible_at` |
| GET | `/api/admin/reports` | Sí (rol ADMIN/MODERATOR) | Cola de reportes pendientes |
| POST | `/api/admin/reports/{id}/action` | Sí (rol ADMIN/MODERATOR) | Resuelve reporte → puede generar `strike` |
| GET | `/legal/dmca` | No | Página estática con datos del agente DMCA |

Todas las respuestas de error deben usar un formato consistente `{ "error": "CODE", "message": "..." }` para que el frontend los maneje de forma uniforme.

---

## 9. Flujo de captura de clips (frontend)

**Caso A — Upload propio:**
`UploadOwnClip.tsx` → selecciona archivo → `POST /api/clips/upload` (multipart) → el backend encola job para `worker` (ffmpeg recorta si excede 20s, normaliza formato) → moderación → publicación.

**Caso B — Import desde link externo (sin descarga server-side):**

1. `ImportFromLink.tsx` recibe la URL, detecta la plataforma (YouTube/Vimeo/Twitch) y renderiza `react-player` con esa URL.
2. El usuario reproduce y marca inicio/fin del fragmento (máx. 20s) en `ClipEditor.tsx`.
3. `useCanvasRecorder.ts` dibuja los frames del `<video>` interno de `react-player` sobre un `<canvas>` oculto, y usa `canvas.captureStream()` + `MediaRecorder` para grabar ese fragmento localmente en el navegador.
4. Si el usuario agrega una pista de audio propia, se mezcla client-side con el `AudioContext` API antes de finalizar la grabación, o se envía por separado y se mezcla en el worker con ffmpeg (decisión de implementación: mezclar client-side es más simple para el MVP).
5. El blob resultante (`video/webm` o convertido a `mp4`) se sube con `POST /api/clips/from-capture`, junto con `sourceUrl` (solo como metadata informativa, nunca se usa para descargar nada server-side).

Nota para el agente: `react-player` debe configurarse respetando los Términos de Servicio de cada plataforma (uso del reproductor oficial embebido, no de streams directos ni extracción). No implementar ninguna ruta que llame a `yt-dlp` o equivalente — quedó descartado por diseño legal (ver conversación previa / sección 2).

---

## 10. Pipeline de moderación (CSAM hashing)

Implementar como consumidor de cola (`worker` module):

1. Job recibido con `clipId` y `filePath`.
2. Extraer N frames representativos del clip con `ffmpeg` (`-vf fps=1` o similar).
3. Calcular hash perceptual PDQ de cada frame.
4. Comparar contra la lista de hashes conocidos (integración real pendiente de aprobación NCMEC — implementar `CsamHashService` como interfaz con una implementación `MockCsamHashService` para desarrollo local, y dejar `NcmecCsamHashService` como stub con `TODO: conectar credenciales tras aprobación de membresía ESP`).
5. Si hay coincidencia → `clips.status = 'REJECTED'`, `moderation_logs` con `result = 'FLAGGED'`, y disparar `NcmecReportClient.report(...)` (también stub, `TODO: integrar CyberTipline API real`).
6. Si no hay coincidencia → `clips.status = 'PUBLISHED'`.

Este pipeline corre **siempre**, tanto para uploads propios como para capturas desde link externo.

---

## 11. Comentarios y mecanismos anti-abuso

Requisito: cualquiera puede comentar un clip publicado, con o sin sesión iniciada. La combinación "abierto a cualquiera" + "sin cuenta" es exactamente el perfil de mayor riesgo de spam/abuso, así que el diseño distingue explícitamente entre autor `USER` (con cuenta) y `GUEST` (invitado), aplicando controles más estrictos al segundo.

### 11.1 Modelo de datos

```sql
-- V6__comments.sql
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
    anon_session_id UUID,                  -- cookie firmada de larga duración, ver 11.3 (también se guarda para USER, útil en investigaciones)
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
```

### 11.2 Rate limiting (Redis, ventana deslizante)

Implementar en `RateLimitService` con Redis (`INCR` + `EXPIRE`, o un algoritmo de sliding window si se quiere más precisión). Límites de referencia, configurables por variable de entorno:

| Autor | Límite |
|---|---|
| Usuario autenticado, cuenta con >7 días y sin strikes | 1 comentario cada 5s, máx. 30/hora |
| Usuario autenticado nuevo (<7 días) o con algún strike | 1 comentario cada 15s, máx. 10/hora |
| Invitado (`GUEST`) | 1 comentario cada 30s, máx. 5/hora **por `ip_hash` Y por `anon_session_id`** (se aplica el límite que se alcance primero) |

Doble clave (`ip_hash` + `anon_session_id`) porque una IP sola no es confiable: redes móviles y NAT comparten IP entre muchas personas reales (castigaría a inocentes), mientras que la cookie sola se puede borrar. Usar ambas y aplicar el límite más restrictivo de los dos reduce ambos falsos positivos y falsos negativos.

### 11.3 Cookie de sesión anónima

Al cargar el sitio sin sesión de usuario, el backend emite una cookie `httpOnly`, `secure`, firmada, con un UUID (`anon_session_id`), duración ~1 año. No identifica a la persona (no es PII por sí sola) pero permite correlacionar el comportamiento del mismo navegador a través de múltiples visitas, lo cual hace mucho más efectivos el rate-limiting y el shadow-ban que depender solo de la IP.

### 11.4 CAPTCHA para invitados

Cloudflare Turnstile (gratuito, más liviano que reCAPTCHA clásico) es obligatorio en cada envío de comentario cuando no hay JWT válido en la request. El backend valida el token de Turnstile contra la API de Cloudflare antes de insertar el comentario. Usuarios autenticados no lo necesitan salvo que su cuenta ya haya disparado alguna señal de riesgo (ver 11.6).

### 11.5 Filtro automático de contenido (pre-publicación, distinto del hashing de CSAM en video)

Se ejecuta de forma síncrona al recibir el comentario (es texto, es rápido — no necesita cola como el video):

- **Lista de patrones prohibidos** (spam conocido, phishing, slurs) → si coincide, el comentario se guarda con `status = PENDING_REVIEW` en vez de bloquearse directamente, para minimizar falsos positivos (sobre todo dado que el español tiene muchas variantes regionales que un filtro rígido puede marcar mal).
- **Detección de URLs**: comentarios de `GUEST` que contienen links quedan automáticamente en `PENDING_REVIEW` — la enorme mayoría del spam de bots incluye enlaces. Para `USER` con cuenta antigua y sin strikes, se permite libremente.
- **`content_hash` duplicado**: si el mismo texto normalizado se repite más de N veces en M minutos desde distintos `ip_hash` (patrón de flood/bot coordinado), se banean automáticamente esos orígenes vía `blocked_origins`.

### 11.6 Shadow-ban / bloqueo silencioso

En vez de devolver un error explícito "tu comentario fue bloqueado" (que le confirma al abusador que debe cambiar de estrategia), un origen marcado como abusivo entra en `blocked_origins` y sus comentarios se guardan con `status = HIDDEN`: el propio autor los sigue viendo con normalidad en su navegador (misma cookie), pero nadie más los ve. Esto es mucho más efectivo contra bots y trolls persistentes que un bloqueo visible.

Escalamiento sugerido: 1ª señal de riesgo (ej. primer comentario marcado por el filtro) → exigir Turnstile aunque sea invitado recurrente; 2ª señal → reducir el límite de rate-limit a la mitad; 3ª señal → shadow-ban del origen (`ip_hash` + `anon_session_id`) por 24-72h vía `blocked_origins`.

### 11.7 Reportes de comentarios

Mismo pipeline que los reportes de clips (tabla `reports`, ahora polimórfica vía `target_type`). Cualquiera puede reportar un comentario, autenticado o no. Cuando `comments.report_count` supera un umbral (ej. 5 reportes desde `ip_hash`/usuarios distintos, para evitar que una sola persona con varias cuentas fuerce la ocultación), el comentario pasa automáticamente a `PENDING_REVIEW` mientras un moderador lo revisa.

### 11.8 API de comentarios

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| GET | `/api/clips/{id}/comments` | No | Lista comentarios `VISIBLE` de un clip, paginado |
| POST | `/api/clips/{id}/comments` | Opcional (JWT si existe, si no se trata como `GUEST`) | Crea comentario; exige `turnstileToken` si es `GUEST`; aplica rate-limit y filtro de 11.5. Si es `GUEST`, rechaza (422) cualquier `attachments` en el payload — ver 11.9 |
| POST | `/api/comments/attachments/image` | Sí (`USER`, email verificado) | Multipart: sube una imagen, calcula `content_hash`, encola moderación CSAM, devuelve un `attachmentId` pendiente para referenciar al crear el comentario |
| POST | `/api/comments/{id}/report` | No | Crea `reports` con `target_type = COMMENT` |
| DELETE | `/api/comments/{id}` | Sí (dueño si es `USER`, o admin/moderador) | Soft delete; los `GUEST` no pueden borrar su propio comentario al no tener sesión — se cubre solo por reporte + moderación |
| GET | `/api/admin/comments/pending` | Sí (rol ADMIN/MODERATOR) | Cola de comentarios en `PENDING_REVIEW` |

### 11.9 Adjuntos para usuarios autenticados: imágenes, referencias a clips y enlaces

Los `GUEST` solo pueden enviar `body` (texto plano). Los `USER` autenticados pueden además adjuntar una imagen, referenciar otro clip existente de la plataforma, o incluir un enlace externo — pero cada tipo trae su propio requisito de seguridad, y esta restricción se aplica en **dos capas** (API + base de datos), no solo en el frontend:

```sql
-- extensión de V6__comments.sql
CREATE TYPE attachment_type AS ENUM ('IMAGE', 'CLIP_REFERENCE', 'LINK');
CREATE TYPE attachment_moderation_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED');

CREATE TABLE comment_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    comment_id UUID NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
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
    embed_platform VARCHAR(20),              -- YOUTUBE | VIMEO | TWITCH | TIKTOK | INSTAGRAM | FACEBOOK | NULL (no reconocido)
    embed_external_id VARCHAR(150),          -- id del video en la plataforma origen, extraído del URL
    embed_title TEXT,                        -- vía oEmbed, solo informativo
    embed_thumbnail_url TEXT,
    is_embeddable BOOLEAN NOT NULL DEFAULT FALSE,

    moderation_status attachment_moderation_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_attachment_payload CHECK (
        (attachment_type = 'IMAGE' AND image_path IS NOT NULL) OR
        (attachment_type = 'CLIP_REFERENCE' AND referenced_clip_id IS NOT NULL) OR
        (attachment_type = 'LINK' AND link_url IS NOT NULL)
    )
);
CREATE INDEX idx_comment_attachments_comment ON comment_attachments(comment_id);
CREATE INDEX idx_comment_attachments_link_domain ON comment_attachments(link_domain);

-- lista de dominios conocidos como maliciosos/phishing/ilegales — alimentable manualmente
-- y/o desde una API externa de reputación (ver TODO abajo)
CREATE TABLE blocked_link_domains (
    domain VARCHAR(255) PRIMARY KEY,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- defensa en profundidad: aunque el backend ya valide "GUEST no puede adjuntar nada",
-- la base de datos lo rechaza también a nivel de trigger, para que un bug en la capa de
-- aplicación no se convierta en un bypass de la regla
CREATE OR REPLACE FUNCTION prevent_guest_attachments() RETURNS TRIGGER AS $$
BEGIN
    IF (SELECT author_type FROM comments WHERE id = NEW.comment_id) = 'GUEST' THEN
        RAISE EXCEPTION 'Los comentarios de invitados no admiten adjuntos';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_guest_attachments
    BEFORE INSERT ON comment_attachments
    FOR EACH ROW EXECUTE FUNCTION prevent_guest_attachments();
```

**Reglas por tipo de adjunto:**

- **`IMAGE`** — se sube primero vía `POST /api/comments/attachments/image`, que la encola en el mismo worker de hashing perceptual (`PdqHashWorker`, sección 10) usado para los frames de video: PDQ funciona igual sobre una imagen suelta. El comentario que la referencia queda en `status = PENDING_REVIEW` hasta que el `attachment.moderation_status` pase a `APPROVED`. Si el hash coincide con la base de CSAM, se aplica exactamente el mismo flujo de `csam_hash_matches` + reporte a NCMEC + baneo inmediato que en clips — una imagen en un comentario no es un caso legal distinto a un clip.
- **`CLIP_REFERENCE`** — el backend valida que `referenced_clip_id` exista y tenga `moderation_status = PUBLISHED` antes de aceptar el comentario; no requiere nueva moderación porque el clip referenciado ya la pasó al publicarse.
- **`LINK`** — no se re-aloja el destino (nunca se descarga ni se previsualiza el contenido del sitio externo en el servidor, solo se guarda la URL). Al guardar, se extrae `link_domain` y se compara contra `blocked_link_domains`; si coincide, el comentario entra directo a `PENDING_REVIEW`. Dejar `LinkSafetyService` como interfaz con implementación mock, y un `TODO: integrar Google Safe Browsing API (tier gratuito) para chequeo automático de phishing/malware` — igual patrón que `CsamHashService` en la sección 10, reemplazable sin tocar el resto del flujo.

**Interstitial de advertencia al hacer click (frontend):**

Ningún enlace dentro de un comentario se renderiza como `<a href>` navegable directo. `ExternalLinkGuard.tsx` intercepta el click sobre cualquier link (tanto los que vienen como `attachment_type = LINK` como los que el usuario escribió sueltos dentro del `body`, detectados por regex al renderizar) y muestra un modal antes de navegar:

> "Vas a salir de ClipShare hacia **`{dominio}`**. No verificamos el contenido de sitios externos. Si te parece dañino, engañoso o ilegal, repórtalo en vez de continuar."
> Botones: **Continuar de todas formas** (abre en pestaña nueva con `rel="nofollow noopener noreferrer ugc"`) · **Reportar este enlace** (abre `ReportForm` pre-cargado con `target_type = COMMENT`, `reason = OTHER`) · **Cancelar**.

`rel="nofollow ugc"` además evita que el sitio termine transfiriendo autoridad de SEO a enlaces de spam insertados por terceros, que es un vector de abuso común en cualquier plataforma con comentarios abiertos.

### 11.10 Vista previa embebida cuando el link es de una plataforma de video

Si el `LINK` que adjunta un `USER` (o el que escribió suelto dentro del `body` — ver nota al final) apunta a una plataforma de video reconocida, el comentario debe mostrar el video reproducible ahí mismo, no solo el link crudo. Aplica el mismo principio legal ya establecido para la importación de clips (sección 9): **siempre reproductor oficial embebido, nunca descarga ni scraping del contenido del video en el servidor.**

**`VideoEmbedResolverService`** — interfaz con una implementación por plataforma, resuelta de forma síncrona al crear el comentario (es solo una llamada rápida a un endpoint de oEmbed, no requiere cola):

| Plataforma | Mecanismo | Requiere credenciales |
|---|---|---|
| YouTube | oEmbed público (`youtube.com/oembed`) | No |
| Vimeo | oEmbed público (`vimeo.com/api/oembed.json`) | No |
| Twitch | Construcción directa del iframe embed a partir del ID extraído del URL | No |
| TikTok | oEmbed público (`tiktok.com/oembed`) | No |
| Facebook | oEmbed de Meta Graph API | Sí — requiere app registrada en Meta for Developers + access token |
| Instagram | oEmbed de Meta Graph API | Sí — requiere app registrada y aprobada por Meta (acceso restringido desde 2020) |

Para Facebook e Instagram, implementar el resolver como stub (`FacebookEmbedResolver`, `InstagramEmbedResolver`) con `TODO: registrar app en Meta for Developers y solicitar acceso al oEmbed Read API` — mientras no esté configurado, `is_embeddable = false` para esos dominios y el comentario simplemente muestra el link normal (con su interstitial de advertencia al hacer click, como cualquier otro enlace no reconocido). Mismo patrón de "interfaz reemplazable" que `CsamHashService` y `LinkSafetyService`.

**Renderizado (`CommentLinkPreview.tsx`):**

- Si `is_embeddable = true` y la plataforma es YouTube, Vimeo o Twitch → `react-player` directo (ya integrado para el flujo de creación de clips).
- Si es TikTok (o Facebook/Instagram una vez configurados) → wrapper dedicado que construye el iframe a partir del `embed_external_id`, **sin inyectar el HTML crudo que devuelve el oEmbed directamente vía `dangerouslySetInnerHTML`** — el HTML de un oEmbed de terceros no es contenido de confianza y es un vector de XSS. En su lugar, extraer solo el ID/URL del embed de la respuesta y construir el `<iframe>` manualmente con `sandbox` apropiado, o sanitizar con DOMPurify si no queda alternativa.
- Si `is_embeddable = false` → tarjeta de link simple (miniatura vía metadatos Open Graph públicos del sitio, que es solo lectura de metadata pública, no descarga de contenido) con un botón "Ver en {dominio} ↗" que sigue pasando por `ExternalLinkGuard` (sección 11.9) — el interstitial de advertencia aplica a la **navegación fuera del sitio**, no a mirar el video embebido sin salir de ClipShare, así que un video embebido reproducible no dispara el aviso; el botón de "ver en la plataforma original" sí.

**Endpoint auxiliar para previsualizar antes de publicar (UX):**

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| GET | `/api/link-preview?url=...` | Sí (`USER`) | Resuelve el link con `VideoEmbedResolverService` en caliente, para mostrarle al usuario la vista previa mientras escribe el comentario, antes de enviarlo |

**Nota sobre links escritos sueltos en el `body`:** aunque el usuario no use el selector de adjuntos y simplemente pegue una URL dentro del texto, el backend escanea el `body` con regex al crear el comentario y, si detecta una URL de plataforma de video reconocida, la resuelve igual y la promueve automáticamente a una fila `comment_attachments` de tipo `LINK` — así el renderizado siempre pasa por el mismo camino, sin importar si el link vino del selector estructurado o del texto libre.

---

## 12. Autenticación

- Registro con email + password (hash con BCrypt). Cuenta queda en `PENDING_VERIFICATION` hasta confirmar email.
- Login devuelve JWT de acceso de corta duración (ej. 15-30 min) + refresh token de larga duración persistido (hasheado) en `refresh_tokens`, para poder revocar sesiones (logout, baneo, "cerrar sesión en todos los dispositivos").
- Con `email_verified_at` sin completar, la cuenta igual puede publicar clips (`/api/clips/upload` y `/api/clips/from-capture`), pero limitada a 3 por día (ventana de 24h, contador en Redis compartido entre ambos endpoints) — devuelven 429 al superarla. Sin este límite, una cuenta desechable sin verificar podría usarse para spam/abuso a volumen; con él, sigue sirviendo para probar el producto sin fricción pero acota el daño. Verificar el email levanta el límite.
- Middleware de Spring Security protege todos los endpoints salvo `/api/auth/**`, `/api/clips/feed`, `/api/clips/{id}` (GET), `/api/reports` (POST) y `/legal/**`.
- Rol vía `users.role` (`USER` | `MODERATOR` | `ADMIN`, enum en la base de datos) para endpoints de moderación (`/api/admin/**`, Fase 5).

> Nota de implementación (Fase 5): a propósito no existe ningún endpoint para auto-promoverse a `ADMIN`/`MODERATOR` (sería una superficie de escalación de privilegios). Para el primer admin en dev/local, promoverlo a mano en la base:
> ```sql
> UPDATE users SET role = 'ADMIN' WHERE email = 'vos@example.com';
> ```

---

## 13. Variables de entorno (`.env.example`)

```
# Backend
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/clipshare
SPRING_DATASOURCE_USERNAME=dev
SPRING_DATASOURCE_PASSWORD=dev
SPRING_REDIS_HOST=redis
JWT_SECRET=change-me-in-dev
STORAGE_MODE=local
STORAGE_LOCAL_PATH=/data/clips

# Comentarios / anti-abuso
TURNSTILE_SECRET_KEY=change-me-in-dev        # Cloudflare Turnstile, requerido para validar comentarios de invitados
ANON_SESSION_COOKIE_SECRET=change-me-in-dev  # firma la cookie anon_session_id
COMMENT_RATE_LIMIT_GUEST_PER_HOUR=5
COMMENT_RATE_LIMIT_USER_PER_HOUR=30

# Producción (referencia futura, no usado en local)
STORAGE_MODE=s3
S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
S3_BUCKET=clipshare-prod
S3_ACCESS_KEY=
S3_SECRET_KEY=
NCMEC_ESP_API_KEY=
DMCA_AGENT_NAME=
DMCA_AGENT_EMAIL=
TURNSTILE_SITE_KEY=
```

---

## 14. Plan de implementación por fases (para Claude Code)

**Fase 0 — Scaffolding**
Crear estructura de carpetas, `docker-compose.yml`, Dockerfiles base, proyecto Spring Boot vacío (con Spring Initializr deps: Web, Security, Data JPA, Validation, PostgreSQL driver, Flyway, Redis), proyecto React con Vite + TypeScript. Verificar que `docker compose up` levanta los 5 servicios sin errores.

**Fase 1 — Auth**
Migraciones Flyway `V1__init.sql`. Endpoints de registro/login. `AuthContext` en React con formularios de login/registro. Verificar flujo completo end-to-end.

**Fase 2 — Upload propio + procesamiento básico**
Endpoint `/api/clips/upload`, cola Redis, worker que invoca ffmpeg para normalizar/recortar a ≤20s. `UploadOwnClip.tsx`. Feed básico (`ClipFeed.tsx`) mostrando clips `PUBLISHED` — para probar el pipeline completo, en esta fase el moderador puede ser un mock que aprueba todo automáticamente.

**Fase 3 — Import desde link externo**
`ImportFromLink.tsx` + `react-player` + `useCanvasRecorder.ts`. `ClipEditor.tsx` con trim UI. Endpoint `/api/clips/from-capture`.

**Fase 4 — Moderación real**
`CsamHashService` (interfaz + mock), `ModerationLog`, lógica de `strikes` y suspensión al 3er strike. `ReportController` + `ReportForm.tsx` (formulario público de DMCA/abuso). Página `/legal/dmca`.

**Fase 5 — Pulido**
Manejo de errores consistente, paginación del feed, roles admin, panel simple de reportes pendientes (`/admin/reports`).

**Fase 6 — Comentarios y anti-abuso**
Migración `V6__comments.sql`. `CommentController` (crear/listar/reportar/borrar), `RateLimitService` (Redis, sliding window por `user_id`/`ip_hash`/`anon_session_id`), integración de Cloudflare Turnstile en el flujo de invitados, filtro automático de contenido (spam/links/duplicados), lógica de shadow-ban. `CommentForm.tsx`, `CommentList.tsx`, cookie de `anon_session_id` en el frontend.

**Fase 6b — Adjuntos de comentarios (imagen / clip / link)**
Tabla `comment_attachments` + trigger `trg_prevent_guest_attachments`. Endpoint `POST /api/comments/attachments/image` reutilizando `PdqHashWorker`. Validación de `referenced_clip_id`. `LinkSafetyService` (mock) + tabla `blocked_link_domains`. `ExternalLinkGuard.tsx` con el modal de advertencia antes de navegar a un enlace externo.

**Fase 6c — Embeds de video en comentarios**
`VideoEmbedResolverService` con implementaciones para YouTube/Vimeo/Twitch/TikTok (funcionales) y Facebook/Instagram (stub, `TODO` app de Meta). Escaneo de `body` por URLs sueltas y promoción automática a `comment_attachments`. `CommentLinkPreview.tsx` (dispatch por plataforma, sin `dangerouslySetInnerHTML` crudo). Endpoint `GET /api/link-preview`.

El agente debe entregar cada fase con tests mínimos (unitarios para servicios críticos como `ModerationService` y el cálculo de strikes; al menos un test de integración para el endpoint de upload) antes de pasar a la siguiente.

---

## 15. Criterios de aceptación / Definition of Done

- [ ] `docker compose up --build` levanta todo el stack sin pasos manuales adicionales.
- [ ] No es posible publicar ni subir un clip sin sesión iniciada.
- [ ] Ningún endpoint ni servicio descarga video completo desde una plataforma externa — la única fuente de bytes de video externo es el blob ya recortado que sube el navegador.
- [ ] Todo clip pasa por `moderation_logs` antes de tener `status = PUBLISHED`.
- [ ] Al 3er strike confirmado, la cuenta pasa a `SUSPENDED` automáticamente y queda registrado.
- [ ] Existe endpoint público de reporte (`/api/reports`) y página `/legal/dmca` (aunque los datos del agente sean placeholder hasta el registro real).
- [ ] Integraciones pendientes de trámite externo (NCMEC, DMCA agent real) están claramente marcadas con `TODO` y aisladas detrás de interfaces reemplazables.
- [ ] Comentarios de invitados (sin sesión) requieren CAPTCHA y quedan sujetos a rate limiting más estricto que los de usuarios autenticados.
- [ ] Un origen (`ip_hash`/`anon_session_id`) marcado como abusivo puede quedar en shadow-ban (sus comentarios no desaparecen para él, pero nadie más los ve) sin exponer el bloqueo al usuario.
- [ ] Un comentario que acumula reportes por encima del umbral configurado pasa automáticamente a `PENDING_REVIEW`.
- [ ] Un `GUEST` no puede adjuntar imagen, referencia a clip ni link a un comentario — rechazado tanto por la API como por el trigger `trg_prevent_guest_attachments` a nivel de base de datos.
- [ ] Una imagen adjunta a un comentario pasa por el mismo pipeline de hashing CSAM que un clip antes de ser visible.
- [ ] Ningún link dentro de un comentario navega directo: siempre pasa primero por el interstitial de advertencia con opción de reportar.
- [ ] Un link de YouTube, Vimeo o Twitch dentro de un comentario se muestra reproducible ahí mismo (embed oficial), sin necesidad de salir del sitio ni disparar el interstitial de advertencia.
- [ ] El HTML devuelto por cualquier oEmbed de terceros nunca se inyecta crudo en el DOM sin sanitizar.

---

## 16. Notas de despliegue futuro (referencia, fuera de alcance de esta sesión)

> **Ver `docs/DEPLOY.md` para la guía operativa real** (despliegue a Hetzner ya hecho siguiendo
> esta sección). Lo que sigue queda como la propuesta original de referencia, sin editar.

Presupuesto objetivo: **$100/mes**. Breakdown de referencia (ver conversación previa):

- VPS Hetzner CX32 (4 vCPU/8GB): ~$17–18/mes — corre backend + worker + Postgres + Redis vía Docker Compose en producción.
- Cloudflare R2 (storage): ~$0–3/mes a escala inicial.
- Cloudflare CDN/proxy: $0 (plan gratuito).
- Dominio: ~$1/mes amortizado.
- Email transaccional (Resend/Mailgun): $0 (tier gratuito).
- Registro de agente DMCA: $6 único (renovación cada 3 años).
- Total estimado: ~$20–25/mes, dejando margen dentro del presupuesto de $100/mes para backups, escalado o picos de tráfico.

No implementar esta fase todavía — se documenta aquí solo para que quede como contexto disponible cuando se retome.
