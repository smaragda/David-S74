#!/usr/bin/env bash
# Zkušební obnova zálohy do dočasné databáze v kontejneru postgres a kontrola obsahu (doporučeno čtvrtletně).
# NEPOUŽITÝ PODKLAD (F8). Spouštět jako uživatel z docker skupiny.
#
# Použití:  AGE_IDENTITY=/cesta/k/age-klici.txt ./restore-test.sh /var/backups/s74-vouchers/daily/soubor.dump.age
set -euo pipefail

BACKUP="${1:?Zadejte cestu k záloze (.dump.age)}"
AGE_IDENTITY="${AGE_IDENTITY:?Nastavte AGE_IDENTITY (soubor se soukromým klíčem age)}"
APP_DIR="${APP_DIR:-/opt/s74-vouchers}"
TEST_DB="s74_vouchers_restore_test"

env_value() { grep -E "^$1=" "$APP_DIR/.env" | head -1 | cut -d= -f2-; }
DB_USER="$(env_value DB_USER)"; DB_USER="${DB_USER:-s74_vouchers}"

cd "$APP_DIR"
psql_exec() { docker compose exec -T postgres "$@"; }

cleanup() { psql_exec dropdb -U "$DB_USER" --if-exists "$TEST_DB" || true; }
trap cleanup EXIT

psql_exec dropdb -U "$DB_USER" --if-exists "$TEST_DB"
psql_exec createdb -U "$DB_USER" "$TEST_DB"
age -d -i "$AGE_IDENTITY" "$BACKUP" | psql_exec pg_restore -U "$DB_USER" --no-owner -d "$TEST_DB"

for table in voucher_order voucher email_delivery stripe_event audit_log staff_user; do
  count="$(psql_exec psql -U "$DB_USER" -At -d "$TEST_DB" -c "select count(*) from $table")"
  echo "$table: $count řádků"
done
echo "Obnova proběhla; dočasná databáze $TEST_DB se smaže."
