# S74 vouchery – backend

Spring Boot 4.1 backend pro prodej dárkových voucherů s74.cz: veřejné API (nabídka, platba přes Stripe Checkout, stav objednávky),
webhook, vystavení voucheru, e-mail s QR kódem, rozhraní obsluhy a vedoucího. Zadání a analýza jsou v `../docs/`
(`vouchery-implementacni-analyza.md`, `vouchery-implementacni-zadani.md`, `vouchery-admin-navod.md`).

> **Umístění:** backend je dočasně v podadresáři `backend/` větve `claude/vouchery-impl-4001ac`. README repozitáře říká, že `main`
> obsahuje jen deployovatelný web, proto se `backend/` na `main` nemerguje bez rozhodnutí. Později jde vyjmout do samostatného
> repozitáře: `git subtree split -P backend -b s74-vouchers` (CI workflow `backend/.github/workflows/ci.yml` se tím stane jeho CI).
> Nasazení webu na GitHub Pages (`.github/workflows/deploy-pages.yml`) backend nekopíruje.

## Požadavky

- JDK 21+ (testováno na 25), Maven 3.9+
- Docker (PostgreSQL + Mailpit lokálně, Testcontainers v testech)
- Stripe CLI (`brew install stripe/stripe-cli/stripe`), přihlášené: `stripe login`
- Python 3 (lokální statický web)

## Lokální běh krok za krokem

1. **Služby** (PostgreSQL 16 na 5432, Mailpit: SMTP 1025, UI http://127.0.0.1:8025):

   ```bash
   cd backend && docker compose up -d
   ```

2. **Konfigurace** – zkopírujte `.env.example` do `.env` (je v `.gitignore`, nikdy ho necommitujte):

   ```bash
   cp .env.example .env
   ```

   Doplňte:
   - `STRIPE_API_KEY` – **sandbox** klíč `rk_test_…` (restricted) nebo `sk_test_…`. Ostrý klíč (`_live_`) aplikace při `STRIPE_LIVEMODE=false` odmítne nastartovat.
   - `STRIPE_WEBHOOK_SECRET` – `whsec_…` z kroku 4.
   - `S74_BOOTSTRAP_ADMIN_USERNAME` / `S74_BOOTSTRAP_ADMIN_PASSWORD` (≥ 12 znaků) – první účet vedoucího se vytvoří při prvním startu; po něm řádky odstraňte.

   Volitelné řádky (DB, SMTP) jsou v `.env.example` zakomentované – lokální výchozí hodnoty odpovídají `docker-compose.yml`. Prázdná hodnota by výchozí přepsala.

   Restricted key potřebuje práva: *Checkout Sessions* (write), *PaymentIntents* (read) a *Charges* (read) – viz admin návod 2.3.

3. **Backend** (naslouchá jen na `127.0.0.1:8080`):

   ```bash
   mvn spring-boot:run
   ```

   Bez Stripe klíče, webhook secretu nebo `S74_PUBLIC_BASE_URL` aplikace nenastartuje a chyba říká, která proměnná chybí.

   > Používejte `127.0.0.1`, ne `localhost`: pokud na vašem stroji na portu 8080 poslouchá jiná služba (na `*:8080`), zachytí `localhost`.

4. **Webhooky** v druhém terminálu:

   ```bash
   stripe listen --forward-to 127.0.0.1:8080/api/stripe/webhook
   ```

   Vypíše `whsec_…`; vložte ho do `.env` a backend restartujte. Dokud `stripe listen` běží, secret se nemění.

5. **Web** v kořeni repozitáře:

   ```bash
   python3 -m http.server 8000 --bind 127.0.0.1
   ```

   Adresa backendu je jediné místo v `../js/vouchers-config.js` (`window.S74_VOUCHER_API`, lokálně `http://127.0.0.1:8080`).
   Otevřete http://localhost:8000/vouchery.html. Lokální profil povoluje CORS pro `http://localhost:8000`.

6. **Obsluha / vedoucí:** http://127.0.0.1:8080/login (účet z bootstrapu). Vedoucí má v hlavičce odkaz „Vedoucí“ (`/admin`).
   E-maily s vouchery jsou v Mailpitu (http://127.0.0.1:8025); odesílá je job každých ~30 s.

Testovací karty: `4242 4242 4242 4242` (úspěch), `4000 0000 0000 0002` (zamítnutí), `4000 0027 6000 3184` (3D Secure); libovolné budoucí datum a CVC.

## Testy

```bash
mvn verify
```

Spouští 87 testů na skutečném PostgreSQL 16 v Testcontainers (jeden sdílený kontejner), SMTP přes GreenMail, Stripe přes atrapu
(`CheckoutGateway`). Webhooky se podepisují v testu HMAC-SHA256 testovacím secretem, takže se ověřuje skutečná kontrola podpisu.
Pokryto: generátor a normalizace kódu, validace konfigurace při startu, checkout (cena z ceníku, absence `payment_method_types` v parametrech Stripe,
validace, rate limit, CORS), webhook (podpis, duplicita, `unpaid` → `async_payment_succeeded`, selhání, expirace, nesoulad částky/měny, livemode),
**souběh webhooku + status endpointu = 1 voucher**, refundy a spory, e-mail (obsah, QR dekódovaný ZXing, retry s backoffem 1/5/30/120 min → `FAILED` + attention, resend),
přihlášení a QR flow, lockout, CSRF, role, **souběžné uplatnění = právě jedno úspěšné**, vypršení s fixními hodinami, správa vedoucím (důvod a audit u každého zásahu,
zneplatnění účtu ukončí relace), CSV export.

Kontrola tajemství: `scripts/check-secrets.sh` (i pro pre-commit hook).

## Struktura

```
backend/
  pom.xml  docker-compose.yml  .env.example
  src/main/java/cz/s74/vouchers/
    config  offer  order  stripe  voucher  email  staff  admin  audit  common
  src/main/resources/  application*.yml  db/migration/V1__init.sql  templates/  static/
  deploy/        nepoužité podklady pro F8 (nginx, systemd, deploy.sh, backup.sh) – viz deploy/README.md
  .github/workflows/ci.yml  scripts/check-secrets.sh
```

## Co bylo ověřeno a co ne

Stav k 4. 11. 2026. **Ověřeno** = skutečně spuštěno a výsledek viděn; **neověřeno** = jen napsáno / nelze bez dalšího kroku.

### Ověřeno automaticky (`mvn verify`, 87 testů zelených)

Viz výše – celá tabulka 17.1 analýzy včetně testu souběžného uplatnění, duplicitního webhooku a souběhu vystavení.

### Ověřeno ručně lokálně (Docker PostgreSQL + Mailpit + prohlížeč, **bez** Stripe)

- Backend nastartuje proti docker Postgresu, Flyway migrace + `ddl-auto: validate` projdou (F0 AK).
- Statický web načte nabídku z API přes CORS (`localhost:8000` → `127.0.0.1:8080`), 3 karty, bez horizontálního posunu na šířce 360 px.
- `checkout.html`: validace neshodných e-mailů, chybějícího souhlasu a chybová hláška při nedostupné platební bráně (s fiktivním klíčem; objednávka zůstane `PENDING_PAYMENT`).
- E-mail s voucherem se přes outbox odeslal do Mailpitu (HTML + text, QR jako inline obrázek). QR kód načtený `zbarimg` vede na `http://127.0.0.1:8080/staff/v/<kód>`.
- QR → `/login` → po přihlášení rovnou detail voucheru → uplatnění → obrazovka „UPLATNĚNO – odečtěte 1 000 Kč v pokladně“ na 360 px.
  Při tom se našla a opravila chyba: `staff-ui.css` nebylo veřejné, takže se jeho 403 uložilo jako cíl po přihlášení (test přidán).
- Voucher pro tento test byl do lokální DB vložen ručně (SQL), ne přes Stripe.

### Neověřeno – vyžaduje sandbox klíč v `backend/.env` (postup níže)

Scénáře z kap. 17.2 analýzy, které se ve skutečném Stripe sandboxu ještě **nespouštěly**:

| Scénář | Stav |
|---|---|
| Úspěšná platba `4242…` → 1 voucher → e-mail v Mailpitu → uplatnění | neověřeno (části za Stripe ověřeny testy s atrapou) |
| Skutečný Checkout: čeština, částka, text o propadnutí, přijetí `integration_identifier` a `customer_email` | neověřeno |
| Zamítnutá karta `4000 0000 0000 0002`, opuštění a expirace session | neověřeno |
| Zavření okna po platbě (webhook vystaví voucher sám) | neověřeno |
| Duplicitní webhook skutečně přes `stripe events resend <id>` | neověřeno (duplicita ověřena testem s podepsaným payloadem) |
| Refund v Dashboardu / `stripe refunds create` → voucher `CANCELLED` | neověřeno (logika ověřena testem) |
| Čtení skutečného `charge.refunded` / spor payloadu s API verzí účtu | neověřeno |
| 3D Secure | neověřeno |
| Selhání SMTP s opakováním na skutečném Mailpitu (v testech GreenMail se zastaveným serverem) | neověřeno proti Mailpitu |
| QR naskenovaný iPhonem a Androidem, Safari iOS / Chrome Android | neověřeno (dekódováno jen `zbarimg`, vzhled jen v emulaci 360 px) |

Poznámka k API verzi: SDK `stripe-java 34.0.0` posílá API verzi `2026-09-30.endive`; účet / CLI může mít jinou výchozí verzi.
Endpoint webhooku musí mít stejnou verzi jako SDK (admin návod 2.5). Kód čte z události jen několik polí z surového JSON, takže nesoulad tolerují, ale doporučujeme ji sladit.

### Jak dokončit ověření ve Stripe sandboxu

1. Ve Stripe Dashboardu (přepnuto na **sandbox / testovací režim**) vytvořte restricted key `rk_test_…` s právy z kroku 2 a vložte ho do `backend/.env` jako `STRIPE_API_KEY`.
   Klíč nikdy nevkládejte do chatu, gitu ani logu.
2. `stripe listen --forward-to 127.0.0.1:8080/api/stripe/webhook` → `whsec_…` do `.env` jako `STRIPE_WEBHOOK_SECRET`, backend restartovat.
3. Projděte tabulku výše: nákup na http://localhost:8000/vouchery.html, e-mail v Mailpitu, uplatnění; zamítnutá karta; `stripe events resend <evt_…>` (nesmí vzniknout druhý voucher);
   zastavte Mailpit (`docker compose stop mailpit`) a nakupte → po pokusech `FAILED` na `/admin/attention`, pak ho spusťte a pošlete znovu z detailu; refund (`stripe refunds create --payment-intent pi_…`) → voucher `CANCELLED`.
4. Výsledky doplňte do této tabulky.

### Mimo rozsah této fáze

F8 (nasazení na DigitalOcean) a F9 (ostrý provoz) se nedělaly. `deploy/` obsahuje jen nepoužité podklady (kontrolovány `bash -n`, nespuštěny).
Texty `obchodni-podminky-vouchery.html` (návrh!) a doplnění `soukromi.html` musí zkontrolovat právník; DPH u voucherů konzultovat s účetní (analýza kap. 20).
