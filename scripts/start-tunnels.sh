#!/bin/bash
# Arranca los dos túneles de Cloudflare (backend + frontend), espera a que Cloudflare les
# asigne una URL pública, la escribe en .env, y levanta/actualiza la app con eso — pensado
# para correr automáticamente en cada arranque de la VM (ver el servicio systemd
# clipshare-tunnels.service, instalado a mano una sola vez, ver docs/DEPLOY.md o el chat).
#
# Por qué hace falta esto: un túnel "quick" de cloudflared (sin cuenta/dominio propio, ver
# decisión tomada en el chat de despliegue) genera una URL al azar CADA VEZ que arranca — no
# hay forma de fijarla sin pagar/configurar un dominio. Así que en vez de pelear para que la
# URL no cambie, este script asume que SIEMPRE va a cambiar y reconfigura la app solo.
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND_LOG="backend-tunnel.log"
FRONTEND_LOG="frontend-tunnel.log"

# Por si el script se re-corre a mano (no solo en el arranque de la VM) — mata cualquier
# túnel viejo que hubiera quedado corriendo, para no terminar con dos apuntando a URLs
# distintas al mismo puerto.
pkill -f "cloudflared tunnel --url http://localhost:8080" 2>/dev/null || true
pkill -f "cloudflared tunnel --url http://localhost:5173" 2>/dev/null || true

rm -f "$BACKEND_LOG" "$FRONTEND_LOG"
nohup cloudflared tunnel --url http://localhost:8080 > "$BACKEND_LOG" 2>&1 &
nohup cloudflared tunnel --url http://localhost:5173 > "$FRONTEND_LOG" 2>&1 &

echo "Esperando a que Cloudflare asigne las URLs de los túneles…"
BACKEND_URL=""
FRONTEND_URL=""
for _ in $(seq 1 30); do
  BACKEND_URL=$(grep -o 'https://[a-zA-Z0-9.-]*\.trycloudflare\.com' "$BACKEND_LOG" | head -1 || true)
  FRONTEND_URL=$(grep -o 'https://[a-zA-Z0-9.-]*\.trycloudflare\.com' "$FRONTEND_LOG" | head -1 || true)
  if [ -n "$BACKEND_URL" ] && [ -n "$FRONTEND_URL" ]; then
    break
  fi
  sleep 2
done

if [ -z "$BACKEND_URL" ] || [ -z "$FRONTEND_URL" ]; then
  echo "ERROR: no se pudieron obtener las URLs de los túneles a tiempo (ver $BACKEND_LOG / $FRONTEND_LOG)" >&2
  exit 1
fi

echo "Backend:  $BACKEND_URL"
echo "Frontend: $FRONTEND_URL"

# Actualiza SOLO estas dos claves dentro de .env, conservando cualquier otra variable que ya
# hubiera ahí (ej. RESEND_API_KEY) — antes este script pisaba el archivo entero con "cat >",
# así que cualquier config agregada a mano se perdía en el siguiente reinicio de la VM.
touch .env
grep -v -E '^(VITE_API_BASE_URL|APP_CORS_ALLOWED_ORIGINS)=' .env > .env.tmp || true
mv .env.tmp .env
echo "VITE_API_BASE_URL=$BACKEND_URL" >> .env
echo "APP_CORS_ALLOWED_ORIGINS=$FRONTEND_URL" >> .env

docker compose up -d --build
echo "Listo — la app quedó disponible en $FRONTEND_URL"
