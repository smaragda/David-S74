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

Spouští 88 testů na skutečném PostgreSQL 16 v Testcontainers (jeden sdílený kontejner), SMTP přes GreenMail, Stripe přes atrapu
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
  Dockerfile  .dockerignore
  deploy/        podklady pro F8 pro droplet 1 GB v Dockeru (compose, nginx, deploy.sh, backup.sh) – viz deploy/README.md; native/ = stará varianta bez Dockeru
  .github/workflows/ci.yml  scripts/check-secrets.sh
```

## Co bylo ověřeno a co ne

Stav k 4. 11. 2026. **Ověřeno** = skutečně spuštěno a výsledek viděn; **neověřeno** = jen napsáno / nelze bez dalšího kroku.

### Ověřeno automaticky (`mvn verify`, 88 testů zelených)

Viz výše – celá tabulka 17.1 analýzy včetně testu souběžného uplatnění, duplicitního webhooku a souběhu vystavení.

### Ověřeno ručně lokálně (Docker PostgreSQL + Mailpit + prohlížeč)

- Backend nastartuje proti docker Postgresu, Flyway migrace + `ddl-auto: validate` projdou (F0 AK).
- Statický web načte nabídku z API přes CORS (`localhost:8000` → `127.0.0.1:8080`), 3 karty, bez horizontálního posunu na šířce 360 px.
- `checkout.html`: validace neshodných e-mailů, chybějícího souhlasu a chybová hláška při nedostupné platební bráně (s fiktivním klíčem; objednávka zůstane `PENDING_PAYMENT`).
- E-mail s voucherem se přes outbox odeslal do Mailpitu (HTML + text, QR jako inline obrázek). QR kód načtený `zbarimg` vede na `http://127.0.0.1:8080/staff/v/<kód>`.
- QR → `/login` → po přihlášení rovnou detail voucheru → uplatnění → obrazovka „UPLATNĚNO – odečtěte 1 000 Kč v pokladně“ na 360 px.
  Při tom se našla a opravila chyba: `staff-ui.css` nebylo veřejné, takže se jeho 403 uložilo jako cíl po přihlášení (test přidán).
- Voucher pro tento test byl do lokální DB vložen ručně (SQL), ne přes Stripe.

### Ověřeno ve Stripe sandboxu (8. 11. 2026, `sk_test_…`, `stripe listen`, Stripe CLI 1.42.1)

| Scénář | Výsledek |
|---|---|
| Úspěšná platba `4242…` (500 Kč) | objednávka `PAID`, právě 1 voucher, platnost +12 měsíců, e-mail v Mailpitu (HTML + text, QR dekódovaný `zbarimg` na `/staff/v/<kód>`) |
| Skutečný Checkout | česky, částka 500,00 Kč, text „Voucher je jednorázový. Nevyužitý rozdíl hodnoty propadá.“, předvyplněný e-mail, `integration_identifier` a `locale=cs` přijaty |
| Návratová stránka | po platbě `PAID`, maskovaný e-mail, číslo objednávky (první běh bez zobrazení voucheru; poté přidáno zobrazení voucheru – viz níže) |
| Duplicitní webhook | `stripe events resend <evt_…>` → HTTP 200, počet voucherů beze změny |
| Zamítnutá karta `4000 0000 0000 0002` | Stripe: „Vaše kreditní karta byla odmítnuta“, objednávka zůstala `PENDING_PAYMENT`, voucher nevznikl |
| Opuštěná session | `stripe checkout sessions expire` → webhook `checkout.session.expired` → `EXPIRED` |
| Výpadek SMTP (Mailpit zastaven) | po platbě voucher vznikl, e-mail 5× neodeslán (backoff; čekání mezi pokusy zkráceno ručním posunem `next_attempt_at`) → `FAILED`, objednávka v `/admin/attention`; po spuštění Mailpitu „odeslat znovu“ z detailu v administraci → e-mail dorazil |
| Souběžné uplatnění | dvě relace ve stejný okamžik → právě 1× `VOUCHER_REDEEMED`, 1× `REDEMPTION_REJECTED` |
| Refund (`stripe refunds create`) | skutečný `charge.refunded` → objednávka `REFUNDED`, voucher `CANCELLED` (důvod `REFUND`) |
| Webhooky ostatních typů (`charge.succeeded`, `payment_intent.*`) | přijaty, `IGNORED`, HTTP 200 |
| Úniky do logu | v logu backendu nebyl API klíč, webhook secret ani celý voucherový kód; audit neobsahuje celý kód |

### Voucher na návratové stránce (změna R10)

Po platbě se na `order-success.html` zobrazí voucher (QR, kód, platnost, tlačítko Vytisknout), ale jen `s74.voucher.show-on-return-minutes` (výchozí 60) minut,
jen pro aktivní voucher a zaplacenou objednávku; každé zobrazení je v auditu (`VOUCHER_DISPLAYED`). Ověřeno testy (zobrazí se, po 61 min ne, zrušený voucher ne)
a ručně v prohlížeči na 360 px (na seed objednávce, ne po nové platbě ve Stripe). `0` zobrazování vypne.

### Zbývá neověřit

| Scénář | Stav |
|---|---|
| 3D Secure `4000 0027 6000 3184` | neověřeno |
| Zavření okna po platbě bez návratu na web | neověřeno ručně (webhook vystavuje voucher sám; pokryto testem a pořadím událostí) |
| QR naskenovaný iPhonem a Androidem, Safari iOS / Chrome Android | neověřeno (QR dekódován `zbarimg`, vzhled jen v emulaci 360 px) |
| Restricted key `rk_test_…` s minimálními právy místo `sk_test_…` | neověřeno (E2E běželo se secret key) |
| Odložené platební metody (Klarna se v sandboxu v Checkoutu nabízí) | neověřeno; podle R11 je v Dashboardu vypnout, kód je obslouží |

Pozorování: webhooky ze `stripe listen` měly API verzi `2026-10-28.endive`, zatímco SDK `stripe-java 34.0.0` používá `2026-09-30.endive`. Zpracování
(čtení několika polí ze surového JSON + `retrieve` přes API) s tím bez problému fungovalo.

Endpoint webhooku musí mít v ostrém provozu stejnou API verzi jako SDK (admin návod 2.5); kód nesoulad toleruje, ale doporučujeme verze sladit.

### Jak scénáře zopakovat

1. Ve Stripe Dashboardu (přepnuto na **sandbox / testovací režim**) vytvořte restricted key `rk_test_…` s právy z kroku 2 a vložte ho do `backend/.env` jako `STRIPE_API_KEY`.
   Klíč nikdy nevkládejte do chatu, gitu ani logu.
2. `stripe listen --forward-to 127.0.0.1:8080/api/stripe/webhook` → `whsec_…` do `.env` jako `STRIPE_WEBHOOK_SECRET`, backend restartovat.
3. Projděte tabulku „Ověřeno ve Stripe sandboxu“: nákup na http://localhost:8000/vouchery.html, e-mail v Mailpitu, uplatnění; zamítnutá karta; `stripe events resend <evt_…>` (nesmí vzniknout druhý voucher);
   zastavte Mailpit (`docker compose stop mailpit`) a nakupte → po pokusech `FAILED` na `/admin/attention`, pak ho spusťte a pošlete znovu z detailu; refund (`stripe refunds create --payment-intent pi_…`) → voucher `CANCELLED`.
4. Zbývající body (3D Secure, restricted key, mobilní zařízení) doplňte do tabulky výše.

### Mimo rozsah této fáze

F8 (nasazení na DigitalOcean) a F9 (ostrý provoz) se nedělaly. `deploy/` obsahuje podklady pro nový 1GB droplet s Dockerem: produkční compose stack jsem lokálně spustil a změřil (app ≈ 280 MiB, Postgres ≈ 30 MiB), skripty a nginx na serveru neběžely (`bash -n` jen syntax).
Texty `obchodni-podminky-vouchery.html` (návrh!) a doplnění `soukromi.html` musí zkontrolovat právník; DPH u voucherů konzultovat s účetní (analýza kap. 20).
