#!/usr/bin/env bash
# Zkušební obnova zálohy do dočasné databáze a kontrola obsahu (doporučeno čtvrtletně).
# NEPOUŽITÝ PODKLAD (F8). Spouštět jako root.
#
# Použití:  AGE_IDENTITY=/cesta/k/age-klici.txt ./restore-test.sh /var/backups/s74-vouchers/daily/soubor.dump.age
set -euo pipefail

BACKUP="${1:?Zadejte cestu k zálohe (.dump.age)}"
AGE_IDENTITY="${AGE_IDENTITY:?Nastavte AGE_IDENTITY (soubor se soukromým klíčem age)}"
TEST_DB="s74_vouchers_restore_test"

cleanup() { sudo -u postgres dropdb --if-exists "$TEST_DB" || true; }
trap cleanup EXIT

sudo -u postgres dropdb --if-exists "$TEST_DB"
sudo -u postgres createdb "$TEST_DB"
age -d -i "$AGE_IDENTITY" "$BACKUP" | sudo -u postgres pg_restore --no-owner -d "$TEST_DB"

for table in voucher_order voucher email_delivery stripe_event audit_log staff_user; do
  count="$(sudo -u postgres psql -At -d "$TEST_DB" -c "select count(*) from $table")"
  echo "$table: $count řádků"
done
echo "Obnova proběhla; dočasná databáze $TEST_DB se smaže."
