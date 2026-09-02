#!/bin/bash
# Setup inicial del servidor de Hetzner — se corre UNA sola vez por SSH, a mano, recién creado
# el servidor (ver docs/DEPLOY.md paso 3). Después de esto, los deploys siguientes los hace solo
# el workflow de GitHub Actions (.github/workflows/deploy.yml) en cada push a main.
# Idempotente: correrlo de nuevo no rompe nada si ya se corrió antes.
set -euo pipefail

REPO_URL="https://github.com/ferd9/clipshare.git"
APP_DIR="$HOME/clipshare"

echo "==> Instalando Docker (repo oficial, no el docker.io de apt)..."
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER"
  echo "Docker instalado. Puede hacer falta cerrar/reabrir la sesión SSH para que el grupo" \
       "'docker' tome efecto sin sudo."
else
  echo "Docker ya estaba instalado, sigo."
fi

echo "==> Clonando el repo en $APP_DIR..."
if [ ! -d "$APP_DIR/.git" ]; then
  git clone "$REPO_URL" "$APP_DIR"
else
  echo "$APP_DIR ya existe, sigo."
fi

cd "$APP_DIR"
git checkout main

if [ ! -f ".env" ]; then
  cp .env.prod.example .env
  echo ""
  echo "=================================================================="
  echo " Creé $APP_DIR/.env a partir de .env.prod.example — TODAVÍA VACÍO."
  echo " Completalo a mano (DOMAIN, JWT_SECRET, ANON_SESSION_COOKIE_SECRET,"
  echo " TURNSTILE_SECRET_KEY, POSTGRES_PASSWORD, RESEND_API_KEY, etc.) antes"
  echo " de que el primer deploy funcione — ver docs/DEPLOY.md paso 4."
  echo "=================================================================="
  exit 1
fi

echo "==> Todo listo. El primer 'docker compose -f docker-compose.prod.yml up -d --build' lo" \
     "dispara el push a main (workflow de GitHub Actions) — o corralo acá mismo a mano si" \
     "querés verificar antes de configurar el workflow."
