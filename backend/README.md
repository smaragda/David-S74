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

   > **Stripe CLI musí poslouchat na stejném účtu jako klíč v `.env`.** `stripe listen` bez dalšího použije účet, ke kterému je CLI přihlášené
   > (`stripe whoami`), a to nemusí být sandbox, jehož klíč máte v `.env`. Pak webhooky z vašich plateb nikdy nedorazí a voucher nevznikne
   > (objednávka zůstane `PENDING_PAYMENT`). Spusťte listener s klíčem ze sandboxu, předaným přes proměnnou prostředí (ne přes argument, ať neskončí v seznamu procesů):
   >
   > ```bash
   > set -a; . ./.env; set +a
   > STRIPE_API_KEY="$STRIPE_API_KEY" stripe listen --forward-to 127.0.0.1:8080/api/stripe/webhook
   > ```
   >
   > Takto vypsané `whsec_…` je pro daný sandbox pořád stejné.

5. **Web** v kořeni repozitáře:

   ```bash
   python3 -m http.server 8000 --bind 127.0.0.1
   ```

   Adresa backendu je jediné místo v `../js/vouchers-config.js` (`window.S74_VOUCHER_API`, lokálně `http://127.0.0.1:8080`).
   Otevřete http://localhost:8000/vouchery.html. Lokální profil povoluje CORS pro `http://localhost:8000`.

6. **Obsluha / vedoucí:** http://127.0.0.1:8080/login (účet z bootstrapu). Vedoucí má v hlavičce odkaz „Vedoucí“ (`/admin`).
   E-maily s vouchery jsou v Mailpitu (http://127.0.0.1:8025); odesílá je job každých ~30 s.

### Když jsou porty 8080 / 5432 / 8000 obsazené

Na vývojářském stroji často běží i jiný projekt (zkušenost: Spring aplikace na 8080 a Postgres na 5432). Své služby pak pusťte na jiných portech
a nic cizího nezastavujte:

- **Postgres na 5433:** `docker-compose.yml` publikuje `127.0.0.1:5432`. Přepište ho override souborem (Compose ≥ 2.24) a zvolte vlastní název projektu:

  ```yaml
  # compose-override.yml (necommitovat)
  services:
    postgres:
      ports: !override
        - "127.0.0.1:5433:5432"
  ```

  ```bash
  docker compose -p s74-vouchers-test -f docker-compose.yml -f compose-override.yml up -d
  ```

- **Backend na 8081:** nastavte proměnné prostředí (přebijí `.env` i výchozí hodnoty, `.env` zůstane beze změny):

  ```bash
  SERVER_PORT=8081 \
  S74_PUBLIC_BASE_URL=http://127.0.0.1:8081 \
  DATABASE_URL=jdbc:postgresql://127.0.0.1:5433/s74_vouchers \
  mvn spring-boot:run
  ```

  `stripe listen --forward-to 127.0.0.1:8081/api/stripe/webhook`. Odkazy na QR a návratové URL pak míří na 8081.
- **Web:** `window.S74_VOUCHER_API` v `../js/vouchers-config.js` musí ukazovat na adresu backendu (8081). Soubor ve větvi neměňte; pro test
  si zkopírujte kořen repozitáře (bez `backend/`) do dočasné složky, upravte tam konfiguraci a tu servírujte (`python3 -m http.server 8000 --bind 127.0.0.1`).
  Pokud je obsazený i port 8000, změňte spolu s ním `S74_FRONTEND_BASE_URL` a `S74_CORS_ALLOWED_ORIGINS` v `.env`.

Testovací karty: `4242 4242 4242 4242` (úspěch), `4000 0000 0000 0002` (zamítnutí), `4000 0027 6000 3184` (3D Secure); libovolné budoucí datum a CVC.

## Testy

```bash
mvn verify
```

Spouští 89 testů na skutečném PostgreSQL 16 v Testcontainers (jeden sdílený kontejner), SMTP přes GreenMail, Stripe přes atrapu
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

### Ověřeno automaticky (`mvn verify`, 89 testů zelených)

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

### Opakované ověření (12. 11. 2026, nový běh od nuly)

Znovu spuštěno nezávisle na předchozím běhu: `mvn verify` (89 testů), Docker Postgres + Mailpit na alternativních portech (viz výše), `stripe listen`
se sandbox klíčem, web v prohlížeči.

| Scénář | Výsledek |
|---|---|
| Veřejné API | špatná nabídka / chybějící souhlas / špatný e-mail → 400; cena z požadavku se ignoruje; webhook bez podpisu i se špatným podpisem → 400; CORS jen pro povolený origin; `/actuator/env` a `/actuator/beans` nedostupné; CSP a další hlavičky nastavené |
| Platba `4242…` (500 Kč) přes web → Stripe Checkout | česky, 500,00 Kč, text o propadnutí; po zaplacení objednávka `PAID`, právě 1 voucher `ACTIVE` (platnost +12 měsíců), `checkout.session.completed` `PROCESSED`, ostatní události `IGNORED` |
| E-mail | 1 zpráva v Mailpitu: Reply-To `s74winecafe@seznam.cz`, kód, platnost, podmínky, bez externích obrázků; QR dekódovaný `zbarimg` vede na `/staff/v/<kód>` |
| Návratová stránka po nové platbě | zobrazí voucher (QR, kód, platnost) a maskovaný e-mail |
| Obsluha | QR anonymně → `/login` → po přihlášení detail; zobrazení voucher nespotřebuje; uplatnění bez zaškrtnutého upozornění se neprovede; bez CSRF tokenu 403 |
| Souběžné uplatnění (2 požadavky naráz) | právě 1× `VOUCHER_REDEEMED`, 1× `REDEMPTION_REJECTED` |
| Refund použitého voucheru (`stripe refunds create`) | objednávka `REFUNDED`, voucher zůstane `REDEEMED`, objednávka `attention_required` („řešit individuálně“) |

Při tomto běhu se ukázalo, že Stripe CLI bylo přihlášené k jinému účtu než klíč v `.env` (webhooky pak nedorazí) a že port 8080 / 5432 byl obsazený jiným projektem;
obojí je popsáno v kapitole „Lokální běh“. Krok „zavření okna po platbě“, 3D Secure, `rk_test_` klíč a mobilní zařízení zůstávají neověřené (viz níže).

### Voucher na návratové stránce (změna R10)

Po platbě se na `order-success.html` zobrazí voucher (QR, kód, platnost, tlačítko Vytisknout), ale jen `s74.voucher.show-on-return-minutes` (výchozí 60) minut,
jen pro aktivní voucher a zaplacenou objednávku; každé zobrazení je v auditu (`VOUCHER_DISPLAYED`). Ověřeno testy (zobrazí se, po 61 min ne, zrušený voucher ne)
a ručně v prohlížeči, včetně nové platby ve Stripe (12. 11. 2026). Kdokoli s URL návratové stránky (obsahuje `session_id`) uvidí voucher po celou dobu okna;
je to záměrný kompromis proti původnímu R10, o jeho ponechání rozhodne majitel. `0` zobrazování vypne.

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
2. `stripe listen` se sandbox klíčem (viz krok 4 v „Lokální běh“, ne s účtem, ke kterému je CLI jinak přihlášené) → `whsec_…` do `.env` jako `STRIPE_WEBHOOK_SECRET`, backend restartovat.
3. Projděte tabulku „Ověřeno ve Stripe sandboxu“: nákup na http://localhost:8000/vouchery.html, e-mail v Mailpitu, uplatnění; zamítnutá karta; `stripe events resend <evt_…>` (nesmí vzniknout druhý voucher);
   zastavte Mailpit (`docker compose stop mailpit`) a nakupte → po pokusech `FAILED` na `/admin/attention`, pak ho spusťte a pošlete znovu z detailu; refund (`stripe refunds create --payment-intent pi_…`) → voucher `CANCELLED`.
4. Zbývající body (3D Secure, restricted key, mobilní zařízení) doplňte do tabulky výše.

### Mimo rozsah této fáze

F8 (nasazení na DigitalOcean) a F9 (ostrý provoz) se nedělaly. `deploy/` obsahuje podklady pro nový 1GB droplet s Dockerem: produkční compose stack jsem lokálně spustil a změřil (app ≈ 280 MiB, Postgres ≈ 30 MiB), skripty a nginx na serveru neběžely (`bash -n` jen syntax).
Texty `obchodni-podminky-vouchery.html` (návrh!) a doplnění `soukromi.html` musí zkontrolovat právník; DPH u voucherů konzultovat s účetní (analýza kap. 20).
