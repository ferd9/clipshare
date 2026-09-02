# Despliegue a producción (Hetzner)

Guía operativa real para desplegar ClipShare en Hetzner, siguiendo la recomendación de
`docs/SPEC.md` §16 (esa sección es la propuesta original de referencia; esta es la runbook
concreta). Reemplaza el despliegue anterior en una VM de Google Cloud (dogfooding, compose de
desarrollo detrás de túneles efímeros de Cloudflare) — ver `[[likecoub-clipshare-project]]` /
el historial de git si hace falta recordar ese flujo.

**Estado de este despliegue, léelo antes de anunciar el sitio:** `MODERATION_CSAM_PROVIDER` y
`APP_TURNSTILE_PROVIDER` quedan en `mock` (sin hash-matching CSAM real, sin protección anti-bot
real), y el agente DMCA + el registro ESP ante NCMEC (`docs/SPEC.md` §2, no negociables antes de
producción real) todavía no están hechos — decisión explícita: primero desplegar, resolver esto
después. **No publicites ni compartas el dominio públicamente hasta resolver esos puntos**,
aunque técnicamente el sitio ya esté online y funcionando.

El storage de clips queda en disco local del propio VPS por ahora (no Cloudflare R2 todavía —
`S3StorageImpl` es un stub sin implementar). Migrar a R2 queda como trabajo futuro explícito,
no cubierto acá.

---

## 1. Crear el servidor en Hetzner Cloud

1. Crear cuenta en https://console.hetzner.cloud (si no tenés una).
2. **Security → SSH Keys**: subir tu clave pública SSH (`~/.ssh/id_ed25519.pub` o similar). Si
   no tenés una, generarla con `ssh-keygen -t ed25519`.
3. **Servers → Add Server**:
   - Imagen: Ubuntu 24.04.
   - Tipo: **CX32** (4 vCPU / 8GB RAM / 80GB disco) — el que recomienda `docs/SPEC.md` §16.
   - Región: la que te quede más cerca de tus usuarios.
   - SSH Key: la que subiste en el paso 2.
   - **Backups**: activar el add-on (~20% del costo del server) — snapshot diario automático.
     Con storage local esto es lo que cubre tanto la base de datos como los clips ya publicados
     en un solo lugar, así que vale la pena activarlo desde el arranque.
4. **Firewalls → Create Firewall**: reglas de entrada solo `22/tcp` (SSH), `80/tcp` y `443/tcp`
   (HTTP/HTTPS — Caddy necesita el 80 abierto para validar el certificado Let's Encrypt, aunque
   redirige todo a 443 después). Asignar el firewall al servidor recién creado.
5. Anotar el IP público del servidor — hace falta en el paso siguiente.

## 2. Dominio

1. Comprar un dominio donde prefieras (Cloudflare Registrar es a precio de costo y deja todo
   integrado con Cloudflare si más adelante querés sumar su CDN).
2. Crear un registro **A** (y **AAAA** si Hetzner asignó IPv6) apuntando al IP del servidor.
3. Si el DNS queda en Cloudflare: dejar el registro **sin el proxy naranja** (DNS only / nube
   gris) por ahora — así Caddy puede validar el certificado Let's Encrypt directo contra el
   servidor. Activar el proxy de Cloudflare (CDN) más adelante es una optimización aparte, no
   bloquea nada de este despliegue.
4. Esperar a que el DNS propague (unos minutos a un par de horas) antes del primer deploy —
   Caddy reintenta solo, pero el primer intento puede fallar si el dominio todavía no resuelve.

## 3. Setup inicial del servidor

Por SSH al servidor (`ssh root@<ip>` o el usuario que hayas configurado):

```bash
curl -fsSL https://raw.githubusercontent.com/ferd9/clipshare/main/scripts/hetzner-first-setup.sh | bash
```

O, si preferís revisarlo antes de correrlo: clonar el repo a mano y correr
`scripts/hetzner-first-setup.sh` desde ahí. El script instala Docker, clona el repo en
`~/clipshare` y crea un `.env` vacío a partir de `.env.prod.example` si todavía no existe —
corta ahí mismo con instrucciones para completarlo (paso siguiente).

## 4. Completar los secrets reales

Editar `~/clipshare/.env` en el servidor (nunca se commitea — ver `.gitignore`) con los valores
reales, siguiendo los comentarios de `.env.prod.example`:

- `DOMAIN`: el dominio del paso 2.
- `JWT_SECRET`, `ANON_SESSION_COOKIE_SECRET`, `TURNSTILE_SECRET_KEY`: generar cada uno con
  `openssl rand -base64 48`. No reusar los `change-me-in-dev` de desarrollo.
- `POSTGRES_PASSWORD`: una contraseña real nueva (tampoco `dev`).
- `APP_EMAIL_PROVIDER=resend`, `RESEND_API_KEY`, `APP_EMAIL_FROM`: las mismas credenciales de
  Resend que ya se usaban en el despliegue de Google Cloud.

## 5. Conectar GitHub Actions al servidor nuevo

En GitHub: **Settings → Secrets and variables → Actions**, actualizar (no crear de nuevo, ya
existen del despliegue anterior):

- `DEPLOY_HOST`: el IP del servidor de Hetzner.
- `DEPLOY_USER`: el usuario SSH (`root` u otro).
- `DEPLOY_SSH_KEY`: la clave privada SSH correspondiente a la pública subida en el paso 1.

`.github/workflows/deploy.yml` ya está configurado para disparar en cada push a `main` y correr
`docker compose -f docker-compose.prod.yml up -d --build` en el servidor — no hace falta tocarlo
salvo que cambie la infraestructura.

## 6. Primer deploy

`main` está desactualizado respecto a `develop` (todo el trabajo real vivía ahí). Ponerlo al
día y disparar el deploy:

```bash
git checkout main
git merge --ff-only develop
git push origin main
```

El workflow corre solo. Verificar en GitHub → Actions que terminó en verde, y después:

- `https://<dominio>` carga el frontend.
- Registrar un usuario nuevo, confirmar que llega el email real (vía Resend).
- Subir o importar un clip real de punta a punta (queda publicado en el feed).
- `ssh` al servidor y revisar `docker compose -f docker-compose.prod.yml logs` sin errores de
  arranque en ningún servicio.

## 7. Apagar el entorno anterior

Con Hetzner confirmado funcionando: apagar/borrar la VM de Google Cloud (acceso y decisión del
usuario — fuera del alcance de este repo). El workflow ya no le apunta, pero sigue corriendo
sola hasta que se apague a mano.

---

## Pendiente (fast-follow, no cubierto en este despliegue)

- **Cloudflare R2**: migrar `STORAGE_MODE=local` → `s3` implementando de verdad
  `S3StorageImpl` (hoy es un stub). El procesamiento ffmpeg (`raw/`/`work/`) seguiría en disco
  local igual — solo `public/`, `attachments/` y `audio/` migrarían a objetos R2.
- **CSAM real**: registro ESP ante NCMEC + integración real de hash-matching (`docs/SPEC.md`
  §2/§10), reemplazando `MODERATION_CSAM_PROVIDER=mock`.
- **Turnstile real**: cuenta de Cloudflare Turnstile, reemplazando `APP_TURNSTILE_PROVIDER=mock`.
- **Agente DMCA**: registrarlo ante el U.S. Copyright Office y publicar sus datos en el sitio
  (footer + `/legal/dmca`), por `docs/SPEC.md` §2.
- Backups de base de datos más finos que el snapshot diario del servidor completo (ej. un
  `pg_dump` propio con retención distinta) — no es urgente mientras el volumen de datos sea chico.
