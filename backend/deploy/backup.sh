#!/usr/bin/env bash
# Denní záloha databáze z Docker kontejneru: pg_dump -Fc → šifrování (age) → rotace → volitelná kopie mimo droplet.
# NEPOUŽITÝ PODKLAD (F8). Spouštět jako uživatel z docker skupiny z cronu / systemd timeru (např. 3:30).
#
# Potřebuje na hostiteli: age (apt install age); volitelně rclone. Veřejný klíč age se předá v AGE_RECIPIENT
# (soukromý klíč uložte MIMO server – bez něj nelze zálohu obnovit!).
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/s74-vouchers}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/s74-vouchers}"
AGE_RECIPIENT="${AGE_RECIPIENT:?Nastavte AGE_RECIPIENT (veřejný klíč age, age1…)}"
KEEP_DAILY="${KEEP_DAILY:-30}"
# Volitelně: cílové místo pro kopii mimo droplet (rclone remote:path, např. spaces:s74-backups)
OFFSITE_RCLONE_TARGET="${OFFSITE_RCLONE_TARGET:-}"

# Hodnoty z .env čteme jednotlivě (soubor se nesmí `source`ovat: obsahuje mezery a &).
env_value() { grep -E "^$1=" "$APP_DIR/.env" | head -1 | cut -d= -f2-; }
DB_NAME="$(env_value DB_NAME)"; DB_NAME="${DB_NAME:-s74_vouchers}"
DB_USER="$(env_value DB_USER)"; DB_USER="${DB_USER:-s74_vouchers}"

umask 077
install -d -m 700 "$BACKUP_DIR/daily" "$BACKUP_DIR/monthly"

STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$BACKUP_DIR/daily/${DB_NAME}-${STAMP}.dump.age"

cd "$APP_DIR"
docker compose exec -T postgres pg_dump -U "$DB_USER" -Fc "$DB_NAME" | age -r "$AGE_RECIPIENT" -o "$FILE"
[ -s "$FILE" ] || { echo "Záloha je prázdná: $FILE" >&2; rm -f "$FILE"; exit 1; }
echo "Záloha vytvořena: $FILE ($(du -h "$FILE" | cut -f1))"

# Měsíční kopie (1. den v měsíci) – uchovává se, dokud ji nesmaže člověk (lhůtu určí účetní).
if [ "$(date +%d)" = "01" ]; then
  cp "$FILE" "$BACKUP_DIR/monthly/"
fi

# Rotace denních záloh
find "$BACKUP_DIR/daily" -name "${DB_NAME}-*.dump.age" -type f -printf '%T@ %p\n' \
  | sort -rn | tail -n +"$((KEEP_DAILY + 1))" | cut -d' ' -f2- | xargs -r rm -f

if [ -n "$OFFSITE_RCLONE_TARGET" ]; then
  rclone copy "$BACKUP_DIR" "$OFFSITE_RCLONE_TARGET" --transfers 1
  echo "Kopie mimo droplet odeslána na $OFFSITE_RCLONE_TARGET"
else
  echo "UPOZORNĚNÍ: OFFSITE_RCLONE_TARGET není nastaveno – záloha zůstává jen na tomto serveru." >&2
fi
