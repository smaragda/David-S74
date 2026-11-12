#!/usr/bin/env bash
# Nasazení nové verze na droplet. Spouští se ze stroje vývojáře (z adresáře backend/).
# NEPOUŽITÝ PODKLAD (F8): nebyl nikdy spuštěn na serveru.
#
# Použití:  DEPLOY_HOST=deploy@vouchery.s74.cz ./deploy/deploy.sh
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:?Nastavte DEPLOY_HOST, např. deploy@vouchery.s74.cz}"
APP_DIR="${APP_DIR:-/opt/s74-vouchers}"
SERVICE="${SERVICE:-s74-vouchers}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"

cd "$(dirname "$0")/.."

echo ">> build + testy"
mvn -B verify package

JAR="target/s74-vouchers.jar"
[ -f "$JAR" ] || { echo "Chybí $JAR"; exit 1; }

RELEASE="app-$(date +%Y%m%d-%H%M%S).jar"
echo ">> nahrávám $RELEASE"
scp "$JAR" "$DEPLOY_HOST:/tmp/$RELEASE"

echo ">> přepínám verzi a restartuji službu"
ssh "$DEPLOY_HOST" bash -s -- "$APP_DIR" "$SERVICE" "$RELEASE" "$HEALTH_URL" "$KEEP_RELEASES" <<'REMOTE'
set -euo pipefail
APP_DIR="$1"; SERVICE="$2"; RELEASE="$3"; HEALTH_URL="$4"; KEEP="$5"

sudo install -d -o s74vouchers -g s74vouchers "$APP_DIR/releases"
sudo install -o s74vouchers -g s74vouchers -m 640 "/tmp/$RELEASE" "$APP_DIR/releases/$RELEASE"
rm -f "/tmp/$RELEASE"

PREVIOUS="$(readlink -f "$APP_DIR/app.jar" || true)"
# atomické přepnutí symlinku
sudo ln -sfn "$APP_DIR/releases/$RELEASE" "$APP_DIR/app.jar.new"
sudo mv -T "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"
sudo systemctl restart "$SERVICE"

echo "čekám na health check…"
for i in $(seq 1 30); do
  if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
    echo "OK: aplikace běží ($RELEASE)"
    # úklid starších verzí
    ls -1t "$APP_DIR/releases"/app-*.jar | tail -n +"$((KEEP + 1))" | xargs -r sudo rm -f
    exit 0
  fi
  sleep 2
done

echo "CHYBA: health check selhal, vracím předchozí verzi"
if [ -n "$PREVIOUS" ] && [ -f "$PREVIOUS" ]; then
  sudo ln -sfn "$PREVIOUS" "$APP_DIR/app.jar.new"
  sudo mv -T "$APP_DIR/app.jar.new" "$APP_DIR/app.jar"
  sudo systemctl restart "$SERVICE"
fi
sudo journalctl -u "$SERVICE" -n 50 --no-pager || true
exit 1
REMOTE
