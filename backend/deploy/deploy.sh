#!/usr/bin/env bash
# Nasazení nové verze (Docker) na droplet. Spouští se ze stroje vývojáře z adresáře backend/.
# NEPOUŽITÝ PODKLAD (F8): na serveru nebyl spuštěn (lokálně ověřen jen compose stack a image).
#
# Použití:  DEPLOY_HOST=deploy@vouchery.s74.cz ./deploy/deploy.sh
#
# Postup: testy + jar → image pro linux/amd64 → přenos přes ssh (bez registru) → přepnutí tagu `current`
# → health check → při selhání návrat na `previous`.
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:?Nastavte DEPLOY_HOST, např. deploy@vouchery.s74.cz}"
APP_DIR="${APP_DIR:-/opt/s74-vouchers}"
IMAGE="s74-vouchers"

cd "$(dirname "$0")/.."

echo ">> build + testy"
mvn -B verify package

TAG="$(date +%Y%m%d-%H%M%S)"
echo ">> image $IMAGE:$TAG (linux/amd64)"
docker build --platform linux/amd64 -t "$IMAGE:$TAG" .

echo ">> kopíruji compose soubor a přenáším image"
scp deploy/docker-compose.prod.yml "$DEPLOY_HOST:$APP_DIR/docker-compose.yml"
docker save "$IMAGE:$TAG" | gzip | ssh "$DEPLOY_HOST" 'gunzip | docker load'

echo ">> přepínám verzi"
ssh "$DEPLOY_HOST" bash -s -- "$APP_DIR" "$IMAGE" "$TAG" <<'REMOTE'
set -euo pipefail
APP_DIR="$1"; IMAGE="$2"; TAG="$3"
cd "$APP_DIR"

if docker image inspect "$IMAGE:current" >/dev/null 2>&1; then
  docker tag "$IMAGE:current" "$IMAGE:previous"
fi
docker tag "$IMAGE:$TAG" "$IMAGE:current"
docker compose up -d

echo "čekám na health check…"
for i in $(seq 1 45); do
  status="$(docker inspect -f '{{.State.Health.Status}}' s74-vouchers-app-1 2>/dev/null || echo none)"
  if [ "$status" = "healthy" ]; then
    echo "OK: aplikace běží ($TAG)"
    # úklid: ponechat current, previous a poslední tagy
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 4
done

echo "CHYBA: health check selhal, vracím předchozí verzi"
docker compose logs --tail 60 app || true
if docker image inspect "$IMAGE:previous" >/dev/null 2>&1; then
  docker tag "$IMAGE:previous" "$IMAGE:current"
  docker compose up -d
fi
exit 1
REMOTE
