#!/usr/bin/env bash
# =============================================================================
# DICOM Hub – One-Command Server Docker Deployment Script
# Usage:
#   ./scripts/deploy.sh [version]          # Deploy using pre-built GHCR images or local build
#   ./scripts/deploy.sh v1.0.0 --build     # Build images directly on server and start
# =============================================================================
set -euo pipefail

APP_VERSION="${1:-latest}"
BUILD_MODE="${2:-auto}"
COMPOSE_FILE="docker-compose.prod.yml"

echo "========================================================"
echo "  DICOM Hub – Production Docker Deployment"
echo "  Target Version : ${APP_VERSION}"
echo "========================================================"

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: Docker is not installed on this server."
  exit 1
fi

# 1. Initialize .env from .env.example if missing
if [[ ! -f .env ]]; then
  echo "[1/4] Creating .env configuration from .env.example..."
  cp .env.example .env
  if command -v openssl >/dev/null 2>&1; then
    NEW_AES_KEY="$(openssl rand -base64 32)"
    NEW_DB_PASS="$(openssl rand -hex 16)"
    sed -i.bak "s|HUB_PSEUDONYM_AES_KEY_B64=.*|HUB_PSEUDONYM_AES_KEY_B64=${NEW_AES_KEY}|g" .env
    sed -i.bak "s|POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${NEW_DB_PASS}|g" .env
    rm -f .env.bak
    echo "      Generated unique production AES-256-GCM pseudonym vault key and PostgreSQL password in .env"
  fi
else
  echo "[1/4] Using existing .env configuration."
fi

export APP_VERSION

# 2. Pull or Build Docker Images
if [[ "${BUILD_MODE}" == "--build" ]]; then
  echo "[2/4] Building Docker images from source on server..."
  docker compose -f "${COMPOSE_FILE}" build
else
  echo "[2/4] Attempting to pull pre-built release images (${APP_VERSION})..."
  if ! docker compose -f "${COMPOSE_FILE}" pull backend-api web-frontend 2>/dev/null; then
    echo "      Pre-built images not accessible or private; building images locally from source..."
    docker compose -f "${COMPOSE_FILE}" build
  fi
fi

# 3. Start containers
echo "[3/4] Starting DICOM Hub Docker stack..."
docker compose -f "${COMPOSE_FILE}" up -d --remove-orphans

# 4. Wait for Backend Healthcheck
echo "[4/4] Verifying service health..."
MAX_WAIT=60
ELAPSED=0
until curl -fsS "http://localhost:${HTTP_PORT:-80}/actuator/health" >/dev/null 2>&1 || [[ ${ELAPSED} -ge ${MAX_WAIT} ]]; do
  sleep 3
  ELAPSED=$((ELAPSED + 3))
  echo -n "."
done
echo ""

if curl -fsS "http://localhost:${HTTP_PORT:-80}/actuator/health" >/dev/null 2>&1; then
  echo "========================================================"
  echo "  DICOM Hub is UP and HEALTHY!"
  echo "  - Command Centre UI : http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo localhost):${HTTP_PORT:-80}"
  echo "  - Backend Health    : http://localhost:${HTTP_PORT:-80}/actuator/health"
  echo "  - DICOM Storage SCP : AE Title=${HUB_DICOM_AE_TITLE:-DICOMHUB_AE}, Port=${HUB_DICOM_PORT:-11112}"
  echo "========================================================"
else
  echo "Warning: Health endpoint did not respond within ${MAX_WAIT}s. Check logs with:"
  echo "  docker compose -f ${COMPOSE_FILE} logs -f"
  exit 1
fi
