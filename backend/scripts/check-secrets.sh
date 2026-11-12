#!/usr/bin/env bash
# Kontrola, že v gitem sledovaných souborech nejsou Stripe klíče ani webhook secret.
# Použití: scripts/check-secrets.sh   (CI i pre-commit hook:  ln -s ../../backend/scripts/check-secrets.sh .git/hooks/pre-commit)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Skutečné klíče mají za prefixem aspoň 20 znaků; krátké zástupné hodnoty v testech a dokumentaci projdou.
pattern='(sk|rk|pk)_(live|test)_[A-Za-z0-9]{20,}|whsec_[A-Za-z0-9]{20,}'

if git grep -nIE "$pattern" -- . ':(exclude)backend/scripts/check-secrets.sh' ':(exclude)scripts/check-secrets.sh'; then
  echo "CHYBA: v repozitáři vypadá něco jako tajný klíč (viz výše). Odstraňte to a klíč v dashboardu Stripe zneplatněte." >&2
  exit 1
fi
echo "OK: žádné tajné klíče ve sledovaných souborech."
