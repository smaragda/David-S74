# Implementační analýza: dárkové vouchery S74

> Navazuje na [vouchery-implementacni-zadani.md](vouchery-implementacni-zadani.md) (dále „zadání“). Zadání říká **co**, tento dokument říká **jak**. Je psaný pro LLM / vývojáře, který bude systém implementovat po fázích (kapitola 18). Kroky, které musí udělat člověk (Stripe účet, DNS, server, právní texty), jsou v [vouchery-admin-navod.md](vouchery-admin-navod.md).
>
> Stav k 4. 11. 2026.

## Obsah

1. Výchozí stav
2. Rozhodnutí a výchozí volby
3. Architektura
4. Technologický stack
5. Doménový model a stavy
6. Databázové schéma
7. Voucherový kód
8. Konfigurace
9. Veřejné API
10. Stripe integrace
11. E-maily a QR kód
12. Rozhraní obsluhy
13. Funkce vedoucího
14. Zabezpečení
15. Změny statického webu (tento repozitář)
16. Provoz a nasazení
17. Testování
18. Implementační fáze s akceptačními kritérii
19. Mimo rozsah první verze
20. Rizika a otevřené body
21. Pravidla pro implementující LLM

---

## 1. Výchozí stav

### 1.1 Tento repozitář (`David-S74`)

- Statický web `https://s74.cz` na GitHub Pages, nasazuje se z `main` workflowem `.github/workflows/deploy-pages.yml` (kopíruje `*.html`, `*.css`, `css/`, `js/`, `images/`, `data/`, `fonts/` se **nekopíruje**). Ověřeno 4. 11. 2026: `https://s74.cz/fonts/fonts.css` vrací **404**, přestože ho načítá `index.html` i voucherové stránky → opraveno doplněním `cp -R fonts _site/` do workflow (commit `fix(deploy): publish fonts directory`).
- Pravidlo z `README.md`: `main` obsahuje jen deployovatelný web. Backend proto **nepatří do tohoto repozitáře** (dřívější scaffold byl ze stejného důvodu smazán commitem `fb7ea25`).
- Statické prototypy (jen HTML, žádná logika):
  - `vouchery.html` – nabídka (jediná karta 1 000 Kč), odkaz „Vstup pro obsluhu“, pilulka „Systém je připraven“.
  - `checkout.html` – e-mail + tlačítko, odesílá GET na `order-success.html`.
  - `order-success.html` – statické „Platba proběhla v pořádku“.
  - `voucher-detail.html` – ukázka voucheru s QR placeholderem a kódem `ABCD-1234-EFGH`.
  - `staff-login.html`, `staff-redeem.html` – mock přihlášení a uplatnění.
  - `app.css` – vizuální styl voucherových stránek (tokeny `--ink`, `--paper`, `--accent`, `--olive`, fonty Fraunces/Avenir).
- Navigace hlavní stránky se plní z `data/content.json` → `hlavicka.menu` (položka e-shop byla odstraněna commitem `f0c0c4a` „ahead of vouchers“). Odkaz na vouchery v menu zatím není; `sitemap.xml` a `llms.txt` už `vouchery.html` uvádějí.
- `soukromi.html` – správce **S74 wine&café, s.r.o., IČO 23035111**, Slezská 856/74, Praha 3; web dnes deklaruje „žádné cookies, žádná analytika“. Nákup voucherů přidá zpracování e-mailu a předání údajů Stripe a e-mailové službě → stránku je nutné doplnit.

### 1.2 Historie backendu

Commit `6eaf87e` (4. 5. 2026) obsahoval kompilovatelný scaffold Spring Boot backendu (`backend/`): Java 21, Spring Boot 4.0.6, PostgreSQL, Flyway, stripe-java 32.1.0, balíčky `cz.s74.vouchers.{common,config,email,order,staff,stripe,voucher}`, profily `local/test/prod`, plánované API. **Použít jako výchozí bod** (viz F0), jen aktualizovat verze.

Obnovení scaffoldu do nového repozitáře:

```bash
mkdir ../s74-vouchers && git archive 6eaf87e backend | tar -x -C ../s74-vouchers --strip-components=1
```

### 1.3 Infrastruktura

- DNS `s74.cz` spravuje **Forpsi** (`ns.forpsi.cz/.net/.it`). Apex ukazuje na GitHub Pages, `www` je CNAME na `smaragda.github.io`. Doména **nemá MX ani SPF/DKIM/DMARC** – e-maily z `@s74.cz` dnes nikdo neposílá, ověření odesílací domény je čistý start.
- Veřejný kontakt: `s74winecafe@seznam.cz` (bezplatná schránka Seznam).
- Backend poběží na existujícím dropletu DigitalOcean, kde už běží WordPress (zadání: „Aktualizovat server i WordPress“). Stav serveru (OS, RAM, webový server, databáze) z repozitáře neznáme – ověří admin podle návodu.

---

## 2. Rozhodnutí a výchozí volby

Zadání nechává několik věcí otevřených. Implementace **nesmí čekat** – použije výchozí volbu, vše je konfigurovatelné. Admin je potvrdí nebo změní (návod, kap. 1).

| ID | Otázka | Výchozí volba pro implementaci | Kde se mění |
|---|---|---|---|
| R1 | Hodnoty voucherů | 500, 1 000, 2 000 Kč | `s74.voucher.offers` |
| R2 | Platnost | 12 měsíců od vystavení, včetně posledního dne, časová zóna Europe/Prague | `s74.voucher.validity-months` |
| R3 | Doména backendu | Libovolná, z konfigurace `S74_PUBLIC_BASE_URL`. Doporučení: subdoména `vouchery.s74.cz` (A záznam u Forpsi na IP dropletu), protože QR kódy na vytištěných voucherech žijí rok a odkazují na tuto doménu. Zadání připouští současnou doménu serveru – funguje stejně. | env |
| R4 | Repozitář backendu | Nový privátní repozitář `s74-vouchers` | – |
| R5 | Přihlášení obsluhy | **Formulářové přihlášení se serverovou relací** (Spring Security `formLogin`). Zadání povoluje Basic Auth, ale ve Spring Security je formulář stejně pracný a odstraňuje známé slabiny Basic Auth (odhlášení na sdíleném zařízení, CSRF s automaticky posílanými údaji, ztráta návratové adresy z QR). Zadání tuto náhradu výslovně předjímá. | – |
| R6 | Databáze | PostgreSQL na stejném dropletu, samostatná DB a uživatel | env |
| R7 | E-mailová služba | Libovolná přes SMTP (Spring Mail), odesílatel `vouchery@s74.cz`, Reply-To `s74winecafe@seznam.cz`. Doporučení poskytovatele v admin návodu. | env |
| R8 | QR v první verzi | Ano (levné: knihovna ZXing, obrázek v e-mailu) | – |
| R9 | Oprávnění vedoucího | Role `MANAGER` = oba vlastníci; brigádník má roli `STAFF` | DB |
| R10 | Zobrazit kód na návratové stránce po platbě | **Změněno na žádost majitele (8. 11. 2026):** voucher (kód, QR, platnost) se na návratové stránce zobrazí, ale jen po omezenou dobu po zaplacení (`s74.voucher.show-on-return-minutes`, výchozí 60, `0` = vypnuto), jen dokud je voucher `ACTIVE` a objednávka `PAID`, a každé zobrazení se zapíše do auditu (`VOUCHER_DISPLAYED`, bez celého kódu). Důvod: zákazníci si dělají screenshot a e-mail bývá zpožděný nebo ve spamu. Riziko: odkaz s `session_id` může uniknout (historie, sdílení) – proto časové okno, `no-referrer` a `no-store`. Původní volba byla „ne, jen stav a maskovaný e-mail“. | `application.yml` |
| R11 | Odložené platební metody | V Dashboardu vypnout (jen karty + Apple Pay / Google Pay / Link), kód je přesto obslouží správně. | Stripe Dashboard |
| R12 | PDF příloha voucheru | Mimo v1 (kap. 19); e-mail lze vytisknout | – |
| R13 | Umístění backendu (odchylka od R4, potvrzeno uživatelem) | Backend je v podadresáři `backend/` na větvi `claude/vouchery-impl-4001ac` tohoto repozitáře, ne v separátním repozitáři. Na `main` se bez rozhodnutí nemerguje (README: `main` = jen web); později `git subtree split -P backend` do `s74-vouchers`. CI workflow leží v `backend/.github/workflows/ci.yml`, takže je v tomto repozitáři neaktivní. | – |
| R14 | Rate limiting | Vlastní in-memory klouzavá okna (`SlidingWindowLimiter`) místo Bucket4j – kap. 4 povoluje „Caffeine čítače“, závislost se tím ušetří. Lockout přihlášení trvá, dokud nejstarší neúspěch nevypadne z 15min okna (nejdéle 15 min). | kód |
| R15 | Čtení webhooku | Podpis ověřuje `Webhook.constructEvent`; z těla události se čtou jen potřebná pole z `data.object` (surový JSON přes Jackson), stav platby se vždy dočte z API (`retrieve`). Nezávisí tak na shodě API verze endpointu a SDK. | kód |
| R16 | Lokální adresa backendu | `http://127.0.0.1:8080` (ne `localhost`): na vývojovém stroji na portu 8080 poslouchala jiná služba na `*:8080`, která zachytávala `localhost`. Platí pro `js/vouchers-config.js`, `S74_PUBLIC_BASE_URL` (QR kódy) a `stripe listen --forward-to`. | `js/vouchers-config.js`, `.env` |
| R17 | Healthcheck | Mail health indikátor je vypnutý (`management.health.mail.enabled=false`): výpadek SMTP nesmí shodit `/actuator/health` a tím deploy; e-maily jdou přes outbox s opakováním. | `application.yml` |
| R18 | `expires_at` Checkout Session | `now + 30 min + 60 s` rezerva (Stripe odmítá hodnotu těsně pod 30 min kvůli latenci požadavku). | kód |

---

## 3. Architektura

```
 Zákazník (prohlížeč)                         Obsluha / vedoucí (mobil)
        │                                              │
        ▼                                              ▼
 https://s74.cz  (GitHub Pages, statické HTML+JS)      https://{BACKEND}/staff, /admin
  vouchery.html → checkout.html → order-success.html   (Thymeleaf stránky z backendu)
        │  fetch (CORS: jen https://s74.cz, www)       │  session cookie, CSRF
        ▼                                              ▼
 ┌──────────────────────── droplet DigitalOcean ─────────────────────────┐
 │ nginx :443 (TLS Let's Encrypt) ── proxy ──► Spring Boot :8080 (127.0.0.1)│
 │                                               │        │        │       │
 │                                         PostgreSQL   SMTP   Stripe API  │
 │                                         (localhost)  (out)  (out)       │
 └────────────────────────────────────────────────────────────────────────┘
        ▲
        │  POST /api/stripe/webhook (podepsané události)
     Stripe
```

Komponenty backendu (balíčky pod `cz.s74.vouchers`):

| Balíček | Odpovědnost |
|---|---|
| `config` | Konfigurační properties, Security, CORS, rate limiting, Stripe klient, scheduler |
| `offer` | Ceník (z konfigurace), endpoint nabídky |
| `order` | Objednávky, stavy, endpoint stavu objednávky |
| `stripe` | Vytvoření Checkout Session, webhook, `FulfillmentService`, refund/dispute |
| `voucher` | Voucher, generátor kódu, normalizace, uplatnění |
| `email` | Outbox (`email_delivery`), šablony, QR, odesílací job |
| `staff` | Účty obsluhy, přihlášení, UI obsluhy |
| `admin` | UI vedoucího, export, správa účtů |
| `audit` | Auditní záznamy |
| `common` | Chyby, čas (`Clock`), maskování |

Jedna instance aplikace → in-memory rate limiting a in-memory session jsou v pořádku.

---

## 4. Technologický stack

| Oblast | Volba | Poznámka |
|---|---|---|
| Jazyk / runtime | Java 21 LTS (25 LTS také OK) | podle dostupnosti na serveru |
| Framework | Spring Boot 4.x (aktuální stabilní) | starters: webmvc, security, data-jpa, validation, mail, thymeleaf, actuator, flyway |
| DB | PostgreSQL 16+ | Flyway migrace, `ddl-auto: validate` |
| Stripe | `com.stripe:stripe-java` **34.0.0** | SDK pinuje API verzi `2026-09-30.endive`; webhook endpoint v Dashboardu musí mít **stejnou API verzi** |
| QR | `com.google.zxing:core` + `javase` | PNG |
| Šablony | Thymeleaf | UI obsluhy i e-maily |
| Rate limiting | Bucket4j (in-memory) nebo Caffeine čítače | |
| Testy | JUnit 5, Spring Boot Test, **Testcontainers PostgreSQL** | H2 ze scaffoldu nahradit – zámky a souběh se musí testovat na Postgresu |
| Build / CI | Maven, GitHub Actions `mvn -B verify` | |

---

## 5. Doménový model a stavy

### 5.1 Objednávka (`voucher_order`)

```
PENDING_PAYMENT ──(session completed, payment_status=paid)───────────► PAID
       │         ──(session completed, payment_status=unpaid)──► PAYMENT_PROCESSING
       │                                                    ├─(async_payment_succeeded)─► PAID
       │                                                    └─(async_payment_failed)────► FAILED
       └──(checkout.session.expired)──► EXPIRED

PAID ──(charge.refunded, plná částka)──► REFUNDED
PAID ──(charge.refunded, část)─────────► PAID + attention_required
PAID ──(charge.dispute.created)────────► DISPUTED  (po uzavření sporu výhrou zpět PAID)
```

- Neúspěšná / opuštěná platba se **neopakuje na stejné objednávce** – zákazník založí novou (nová Checkout Session). Staré objednávky dožijí jako `EXPIRED`/`FAILED`.
- `attention_required` + `attention_reason` = příznak pro vedoucího (nesoulad částky, refund použitého voucheru, spor, opakovaně selhaný e-mail).

### 5.2 Voucher (`voucher`)

```
ACTIVE ──(uplatnění obsluhou)──► REDEEMED
ACTIVE ──(zrušení vedoucím / plný refund / spor)──► CANCELLED
REDEEMED ──(oprava vedoucím, s důvodem)──► ACTIVE
CANCELLED ──(reaktivace vedoucím, s důvodem)──► ACTIVE
```

- **Vypršení se neukládá jako stav** – odvozuje se: `today(Europe/Prague) > valid_until` ⇒ zobrazit „Vypršel“, uplatnění zamítnout. Vedoucí může platnost prodloužit (audit).
- Voucher vzniká **jen** při přechodu objednávky do `PAID`, právě jeden na objednávku (`UNIQUE(order_id)`).
- Zobrazení stavu („efektivní stav“) pro UI: `REDEEMED` > `CANCELLED` > `EXPIRED` (odvozeně) > `ACTIVE`.

### 5.3 Doručení e-mailu (`email_delivery`)

`PENDING → SENDING → SENT`, při chybě zpět `PENDING` s `next_attempt_at` (backoff), po 5 pokusech `FAILED` + `attention_required` na objednávce. Opětovné zaslání = nový řádek (`kind=RESEND`).

### 5.4 Stripe událost (`stripe_event`)

Záznam každé přijaté události (ID je PK) – idempotence a audit. `RECEIVED → PROCESSED | IGNORED | FAILED`.

---

## 6. Databázové schéma

Flyway `V1__init.sql` (PostgreSQL). Částky v **haléřích** (`bigint`), měna `CZK`. Časy `timestamptz` (UTC), data platnosti `date` (Europe/Prague).

```sql
create table staff_user (
    id              uuid primary key,
    username        varchar(64)  not null unique,
    display_name    varchar(128) not null,
    password_hash   varchar(255) not null,
    role            varchar(16)  not null check (role in ('STAFF', 'MANAGER')),
    enabled         boolean      not null default true,
    created_at      timestamptz  not null,
    disabled_at     timestamptz
);

create table voucher_order (
    id                          uuid primary key,
    offer_id                    varchar(32)  not null,
    amount_minor                bigint       not null check (amount_minor > 0),
    currency                    char(3)      not null default 'CZK',
    customer_email              varchar(254) not null,
    terms_version               varchar(32)  not null,
    terms_accepted_at           timestamptz  not null,
    status                      varchar(24)  not null check (status in
        ('PENDING_PAYMENT','PAYMENT_PROCESSING','PAID','FAILED','EXPIRED','REFUNDED','DISPUTED')),
    stripe_checkout_session_id  varchar(255) unique,
    stripe_payment_intent_id    varchar(255) unique,
    refunded_amount_minor       bigint       not null default 0,
    attention_required          boolean      not null default false,
    attention_reason            varchar(255),
    created_at                  timestamptz  not null,
    updated_at                  timestamptz  not null,
    paid_at                     timestamptz
);
create index voucher_order_email_idx on voucher_order (lower(customer_email));
create index voucher_order_attention_idx on voucher_order (attention_required) where attention_required;

create table voucher (
    id               uuid primary key,
    order_id         uuid         not null unique references voucher_order (id),
    code             varchar(16)  not null unique,   -- kanonický tvar bez pomlček
    amount_minor     bigint       not null,
    currency         char(3)      not null,
    issued_at        timestamptz  not null,
    valid_until      date         not null,
    status           varchar(16)  not null check (status in ('ACTIVE','REDEEMED','CANCELLED')),
    redeemed_at      timestamptz,
    redeemed_by      uuid references staff_user (id),
    redemption_note  varchar(255),                   -- číslo účtenky apod., volitelné
    cancelled_at     timestamptz,
    cancelled_by     uuid references staff_user (id),
    cancel_reason    varchar(255),
    version          bigint       not null default 0
);

create table email_delivery (
    id                   uuid primary key,
    voucher_id           uuid         not null references voucher (id),
    kind                 varchar(16)  not null check (kind in ('ISSUED','RESEND')),
    recipient            varchar(254) not null,
    status               varchar(16)  not null check (status in ('PENDING','SENDING','SENT','FAILED')),
    attempts             int          not null default 0,
    next_attempt_at      timestamptz  not null,
    last_error           varchar(500),               -- bez tajných údajů
    provider_message_id  varchar(255),
    requested_by         uuid references staff_user (id),
    created_at           timestamptz  not null,
    sent_at              timestamptz
);
create index email_delivery_due_idx on email_delivery (status, next_attempt_at);

create table stripe_event (
    event_id      varchar(255) primary key,
    type          varchar(64)  not null,
    livemode      boolean      not null,
    status        varchar(16)  not null check (status in ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    error         varchar(500),
    received_at   timestamptz  not null,
    processed_at  timestamptz
);

create table audit_log (
    id           bigserial primary key,
    occurred_at  timestamptz not null,
    actor_type   varchar(8)  not null check (actor_type in ('STAFF','SYSTEM','STRIPE')),
    actor_id     uuid references staff_user (id),
    action       varchar(48) not null,
    voucher_id   uuid references voucher (id),
    order_id     uuid references voucher_order (id),
    ip           inet,
    details      jsonb       -- nikdy hesla, tajné klíče ani celý voucherový kód
);
create index audit_log_voucher_idx on audit_log (voucher_id, occurred_at);
```

Auditní akce (`action`): `ORDER_CREATED`, `PAYMENT_CONFIRMED`, `VOUCHER_ISSUED`, `EMAIL_SENT`, `EMAIL_FAILED`, `EMAIL_RESEND_REQUESTED`, `VOUCHER_VIEWED`, `VOUCHER_REDEEMED`, `REDEMPTION_REJECTED`, `REDEMPTION_REVERTED`, `VOUCHER_CANCELLED`, `VOUCHER_REACTIVATED`, `VALIDITY_EXTENDED`, `ORDER_REFUNDED`, `ORDER_DISPUTED`, `ORDER_MISMATCH`, `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOOKUP_NOT_FOUND`, `RATE_LIMITED`, `STAFF_CREATED`, `STAFF_DISABLED`, `STAFF_ENABLED`, `STAFF_PASSWORD_RESET`.

Retence: objednávky a vouchery jsou účetní podklad – nemazat (lhůtu potvrdí účetní). Audit login/lookup událostí lze po 12 měsících mazat (plánovaný job, volitelné).

---

## 7. Voucherový kód

- **Abeceda:** Crockford Base32 `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (bez I, L, O, U).
- **Délka:** 12 znaků = 60 bitů (≈ 1,15 × 10¹⁸ kombinací). Při stovkách aktivních voucherů a limitu 10 neúspěšných pokusů / 15 min na účet je pravděpodobnost uhodnutí zanedbatelná; 12 znaků se ještě pohodlně přepisuje.
- **Zobrazení:** `XXXX-XXXX-XXXX`. **Uložení:** kanonicky, 12 znaků bez pomlček, `UNIQUE`.
- **Generování:** `SecureRandom`, 12× náhodný index do abecedy (bez modulo biasu – 32 je mocnina 2, tedy 5 bitů na znak). Při kolizi (`DataIntegrityViolationException` na `code`) nový pokus, max. 5×.
- **Normalizace vstupu** (obsluha píše ručně): trim → odstranit mezery, pomlčky, tečky → uppercase → Crockford mapování `O→0`, `I→1`, `L→1` → odmítnout, pokud délka ≠ 12 nebo znak mimo abecedu (chyba „Kód má 12 znaků, zkontrolujte jej“ – nepočítá se do limitu hádání). Tolerance mezer a velikosti písmen je požadavek zadání.
- **Kontrolní znak** (volitelné, doporučené): 13. znak = Crockford check symbol (mod 37) by odhalil překlep bez dotazu do DB. Pro v1 **ne** – zvyšuje délku, chyba „nenalezeno“ stačí.
- **Logování:** kód nikdy celý. Maska `****-****-AB12` (`common.Masking`).
- Kód se ukládá v čitelné podobě (potřebný pro opětovné zaslání e-mailu). Ochrana = přístup k DB jen z localhostu, šifrované zálohy.

---

## 8. Konfigurace

`application.yml` (nastíněno, doplnit do scaffoldu):

```yaml
s74:
  public-base-url: ${S74_PUBLIC_BASE_URL}                 # např. https://vouchery.s74.cz (bez lomítka na konci)
  frontend-base-url: ${S74_FRONTEND_BASE_URL:https://s74.cz}
  cors-allowed-origins: ${S74_CORS_ALLOWED_ORIGINS:https://s74.cz,https://www.s74.cz}
  zone: Europe/Prague
  voucher:
    validity-months: 12
    terms-version: "2026-11"
    offers:
      - id: CZK_500
        amount-czk: 500
      - id: CZK_1000
        amount-czk: 1000
      - id: CZK_2000
        amount-czk: 2000
  stripe:
    api-key: ${STRIPE_API_KEY}                 # restricted key rk_test_… / rk_live_…
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}   # whsec_…
    expected-livemode: ${STRIPE_LIVEMODE:false}
    checkout-expiry-minutes: 30
  mail:
    from: ${S74_MAIL_FROM:S74 wine&café <vouchery@s74.cz>}
    reply-to: ${S74_MAIL_REPLY_TO:s74winecafe@seznam.cz}
    max-attempts: 5
  bootstrap-admin:
    username: ${S74_BOOTSTRAP_ADMIN_USERNAME:}
    password: ${S74_BOOTSTRAP_ADMIN_PASSWORD:}
server:
  port: ${SERVER_PORT:8080}
  address: 127.0.0.1
  forward-headers-strategy: native
  servlet.session:
    timeout: 12h
    cookie: { http-only: true, secure: true, same-site: lax, name: S74SESSION }
```

Proměnné prostředí v produkci (soubor `/etc/s74-vouchers/env`, viz admin návod): `SPRING_PROFILES_ACTIVE=prod`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_LIVEMODE`, `S74_PUBLIC_BASE_URL`, `S74_FRONTEND_BASE_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `S74_MAIL_FROM`, volitelně `S74_BOOTSTRAP_ADMIN_*`.

Validace při startu (`@Validated` properties): aplikace **nenastartuje**, pokud chybí Stripe klíč, webhook secret nebo `public-base-url`; v profilu `prod` dále pokud `STRIPE_LIVEMODE=true` a klíč nezačíná `rk_live_`/`sk_live_` (a obráceně) – chrání před záměnou testu a ostrého provozu.

Lokálně: `.env.example` v repozitáři (bez hodnot), skutečný `.env` v `.gitignore`.

---

## 9. Veřejné API

Prefix `/api/public/**` – bez přihlášení, CORS jen pro `cors-allowed-origins`, metody `GET, POST`, hlavička `Content-Type`. Odpovědi JSON, `Cache-Control: no-store`. Chyby jednotně:

```json
{ "error": "INVALID_EMAIL", "message": "Zadejte platnou e-mailovou adresu." }
```

### 9.1 `GET /api/public/offers`

```json
{
  "currency": "CZK",
  "validityMonths": 12,
  "termsVersion": "2026-11",
  "termsUrl": "https://s74.cz/obchodni-podminky-vouchery.html",
  "offers": [
    { "id": "CZK_500",  "amountCzk": 500,  "label": "Voucher v hodnotě 500 Kč" },
    { "id": "CZK_1000", "amountCzk": 1000, "label": "Voucher v hodnotě 1 000 Kč" },
    { "id": "CZK_2000", "amountCzk": 2000, "label": "Voucher v hodnotě 2 000 Kč" }
  ]
}
```

### 9.2 `POST /api/public/checkout-sessions`

Request:

```json
{ "offerId": "CZK_1000", "email": "jana@example.com", "termsAccepted": true, "termsVersion": "2026-11" }
```

Postup:
1. Validace: `offerId` existuje v ceníku (**cena se bere výhradně z ceníku backendu**, nikdy z requestu); e-mail platný (`@Email`, max 254, trim, lowercase domény); `termsAccepted == true`; `termsVersion` = aktuální (jinak `409 TERMS_OUTDATED` → frontend znovu načte nabídku).
2. Rate limit (kap. 14.4).
3. V transakci založit `voucher_order` (`PENDING_PAYMENT`, `terms_accepted_at = now`) + audit `ORDER_CREATED`.
4. Mimo transakci vytvořit Checkout Session (kap. 10.2) s idempotency key `checkout-session-{orderId}`.
5. Uložit `stripe_checkout_session_id`.
6. Odpověď `201`: `{ "checkoutUrl": "https://checkout.stripe.com/…" }`.

Chyby: `400 INVALID_OFFER | INVALID_EMAIL | TERMS_NOT_ACCEPTED`, `409 TERMS_OUTDATED`, `429 RATE_LIMITED`, `502 PAYMENT_PROVIDER_UNAVAILABLE` (Stripe nedostupný – objednávka zůstane `PENDING_PAYMENT` a dožije jako opuštěná).

### 9.3 `GET /api/public/orders/status?session_id=cs_…`

Pro návratovou stránku. Pokud je objednávka ještě `PENDING_PAYMENT`/`PAYMENT_PROCESSING`, zavolá `FulfillmentService.fulfill(sessionId)` (stejná idempotentní funkce jako webhook – doporučení Stripe pro případ zpožděného webhooku). Rate limit.

```json
{
  "status": "PAID",                 // PENDING | PROCESSING | PAID | FAILED | EXPIRED | UNKNOWN
  "orderRef": "7F3A9C21",           // prvních 8 znaků UUID, pro komunikaci se zákazníkem
  "amountCzk": 1000,
  "emailMasked": "j***@e***.com",
  "emailStatus": "SENT"             // PENDING | SENT | FAILED (jen pokud PAID)
}
```

Neexistující `session_id` → `200 { "status": "UNKNOWN" }` (neprozrazovat, nic víc). Voucherový kód vrací jen v časovém okně po zaplacení (R10, upraveno) jako objekt `voucher` (`code`, `validUntil`, `amountCzk`, `qrDataUri`).

### 9.4 `POST /api/stripe/webhook`

Viz kap. 10.3. Bez CORS, bez CSRF, bez session; ověřuje se podpis.

### 9.5 `GET /actuator/health`

Jen z localhostu (nginx tuto cestu ven nepouští).

---

## 10. Stripe integrace

### 10.1 Klient

- Jedna instance `StripeClient` jako Spring bean: `new StripeClient(apiKey)` (případně builder s `setMaxNetworkRetries(2)`). **Nepoužívat** globální `Stripe.apiKey`.
- Klíč: **restricted key** (`rk_…`) s minimálními právy – viz admin návod 2.3. Při `403` z API doplnit právo v Dashboardu, ne přejít na `sk_`.
- Žádný klíč v kódu, logu ani chybové hlášce.

### 10.2 Vytvoření Checkout Session

Parametry (`SessionCreateParams`):

| Parametr | Hodnota |
|---|---|
| `mode` | `payment` |
| `line_items[0].price_data.currency` | `czk` |
| `line_items[0].price_data.unit_amount` | `amount_minor` (1 000 Kč = `100000`) |
| `line_items[0].price_data.product_data.name` | `Dárkový voucher S74 wine&café – 1 000 Kč` |
| `line_items[0].price_data.product_data.description` | `Jednorázový voucher, platnost 12 měsíců od vystavení.` |
| `line_items[0].quantity` | `1` |
| `customer_email` | e-mail z objednávky |
| `client_reference_id` | `order.id` |
| `metadata.order_id` | `order.id` |
| `payment_intent_data.metadata.order_id` | `order.id` (pro dohledání u refundu/sporu) |
| `payment_intent_data.description` | `Voucher S74 {orderRef}` |
| `success_url` | `{frontend}/order-success.html?session_id={CHECKOUT_SESSION_ID}` (literál `{CHECKOUT_SESSION_ID}` doplní Stripe) |
| `cancel_url` | `{frontend}/checkout.html?offer={offerId}&cancelled=1` |
| `expires_at` | `now + 30 min` (minimum, které Stripe dovoluje) |
| `locale` | `cs` |
| `submit_type` | `pay` |
| `custom_text.submit.message` | `Voucher je jednorázový. Nevyužitý rozdíl hodnoty propadá.` |
| `integration_identifier` | konstanta `s74_vouchers_` + 8 náhodných písmen, vygenerovat jednou (pro API ≥ `2026-03-25.dahlia`; pokud SDK parametr nezná, vynechat) |
| `consent_collection.terms_of_service` | volitelně `required` – jen pokud je v Dashboardu vyplněna URL obchodních podmínek, jinak API vrátí chybu |

**Nikdy neposílat `payment_method_types`** – platební metody se řídí v Dashboardu (dynamic payment methods). Neposílat `automatic_tax` (DPH řeší účetní, kap. 20). Nevytvářet Stripe Customer ani Product/Price objekty.

Idempotency: `RequestOptions.builder().setIdempotencyKey("checkout-session-" + orderId)`.

### 10.3 Webhook

Odebírané události (endpoint v Dashboardu, admin návod 2.5):

| Událost | Akce |
|---|---|
| `checkout.session.completed` | `fulfill(session.id)` |
| `checkout.session.async_payment_succeeded` | `fulfill(session.id)` |
| `checkout.session.async_payment_failed` | objednávka → `FAILED` |
| `checkout.session.expired` | `PENDING_PAYMENT` → `EXPIRED` |
| `charge.refunded` | kap. 10.5 |
| `charge.dispute.created` | kap. 10.5 |
| `charge.dispute.closed` | kap. 10.5 |

Zpracování:

1. Controller přijme **surové tělo** (`@RequestBody String payload`) a hlavičku `Stripe-Signature`. Žádný filtr nesmí tělo přečíst/změnit dřív.
2. `Webhook.constructEvent(payload, sigHeader, webhookSecret)` (tolerance 300 s). Neplatný podpis → `400`, audit/log `WEBHOOK_SIGNATURE_INVALID` bez těla.
3. `event.livemode` ≠ `expected-livemode` → `200`, `IGNORED`.
4. `INSERT INTO stripe_event … ON CONFLICT DO NOTHING`. Pokud už existuje se stavem `PROCESSED`/`IGNORED` → `200` (duplicitní doručení).
5. Deserializace přes `event.getDataObjectDeserializer()`. Pokud objekt nejde deserializovat (nesoulad API verze), **nepokračovat naslepo**: použít `deserializeUnsafe()` jen pro ID objektu a zbytek načíst z API (`retrieve`).
6. Zpracovat podle typu; úspěch → `PROCESSED`, `200`.
7. Výjimka → `FAILED` + `error`, odpověď `500` → Stripe doručení zopakuje (v živém režimu až 3 dny). Zpracování proto musí být idempotentní i při opakování po částečném selhání.
8. Neznámý typ → `IGNORED`, `200`.

Webhook má odpovědět rychle: odeslání e-mailu se **neděje v requestu** (outbox, kap. 11).

### 10.4 `FulfillmentService.fulfill(sessionId)`

Jediné místo, kde vzniká voucher. Volá se z webhooku i ze status endpointu.

```
session = stripe.checkout().sessions().retrieve(sessionId)      // aktuální stav, ne payload
orderId = session.metadata["order_id"]; pokud chybí → IGNORED (cizí session)

@Transactional:
  order = SELECT … FROM voucher_order WHERE id = :orderId FOR UPDATE
  pokud order == null → chyba (retry)
  pokud order.status ∈ {PAID, REFUNDED, DISPUTED} → return (idempotence)
  kontroly:
    order.stripe_checkout_session_id == session.id
    session.mode == "payment"
    session.currency == "czk" && session.amount_total == order.amount_minor
  při nesouladu → attention_required, audit ORDER_MISMATCH, log ERROR, return (žádný voucher, nevracet 500)

  pokud session.payment_status == "paid":
      order.status = PAID, paid_at = now, stripe_payment_intent_id = session.payment_intent
      voucher = new Voucher(code = generátor, amount, valid_until = today(Prague) + validity-months, ACTIVE)
      email_delivery = PENDING (kind ISSUED, recipient = order.customer_email, next_attempt_at = now)
      audit PAYMENT_CONFIRMED, VOUCHER_ISSUED
  jinak pokud session.status == "complete" && payment_status == "unpaid":
      order.status = PAYMENT_PROCESSING
  jinak: nic (čeká se)
```

Poslední pojistka proti dvojímu vystavení: `UNIQUE(voucher.order_id)` – při souběhu webhooku a status endpointu jeden čeká na zámek řádku a pak uvidí `PAID`.

### 10.5 Refundy a spory

Refundy se provádějí ručně v Stripe Dashboardu (admin návod 2.8). Backend reaguje:

- `charge.refunded`: najít objednávku podle `charge.payment_intent` (fallback: `retrieve` PaymentIntentu → `metadata.order_id`; pokud objednávka ještě není `PAID`, vyhodit výjimku → Stripe zopakuje). Uložit `refunded_amount_minor = charge.amount_refunded`.
  - plný refund + voucher `ACTIVE` → voucher `CANCELLED` (důvod `REFUND`), objednávka `REFUNDED`;
  - plný refund + voucher `REDEEMED` → objednávka `REFUNDED`, `attention_required` („refund použitého voucheru – řešit individuálně“, zadání);
  - částečný refund → jen `attention_required`, voucher beze změny.
- `charge.dispute.created`: objednávka `DISPUTED`, voucher `ACTIVE` → `CANCELLED` (důvod `DISPUTE`), `attention_required`.
- `charge.dispute.closed`: `status == won` → objednávka zpět `PAID`, `attention_required` (vedoucí rozhodne o reaktivaci voucheru); `lost` → ponechat, `attention_required`.

---

## 11. E-maily a QR kód

### 11.1 Outbox a odesílání

- `@Scheduled(fixedDelay = 30 s)` job: `SELECT … FROM email_delivery WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED` → `SENDING` → odeslat → `SENT` (+ `sent_at`, `provider_message_id` z `Message-ID`, audit `EMAIL_SENT`).
- Chyba: `attempts++`, `last_error` (zkrácená, bez hesel), `next_attempt_at` podle backoffu 1 min, 5 min, 30 min, 2 h; po `max-attempts` (5) → `FAILED`, objednávka `attention_required`, audit `EMAIL_FAILED`.
- Při startu aplikace vrátit `SENDING` starší než 10 min zpět na `PENDING` (pád během odesílání). Riziko duplicitního e-mailu je přijatelné.
- SMTP přes Spring Mail (`JavaMailSender`), STARTTLS (587) nebo SMTPS (465) podle poskytovatele; timeouty 10 s.

### 11.2 Obsah e-mailu (Thymeleaf, HTML + textová alternativa)

- **Od:** `S74 wine&café <vouchery@s74.cz>`, **Reply-To:** `s74winecafe@seznam.cz`
- **Předmět:** `Váš dárkový voucher do S74 wine&café – 1 000 Kč`
- **Obsah:**
  - nadpis „Dárkový voucher“, hodnota velkým písmem;
  - QR kód (inline obrázek `cid:voucher-qr`, PNG 600 × 600, v HTML zobrazit 240 px);
  - kód pro ruční zadání `XXXX-XXXX-XXXX` (monospace, velký);
  - „Platí do: 4. 11. 2027“ (formát `d. M. yyyy`, Europe/Prague);
  - jak uplatnit: ukažte QR nebo kód obsluze při placení v S74 wine&café, Slezská 856/74, Praha 3; otevírací doba odkazem na `https://s74.cz` (**nehardcodovat hodiny** – mění se);
  - podmínky (stručně, odkaz na plné znění): jednorázové uplatnění; je-li útrata nižší, **nevyužitý rozdíl propadá**; je-li vyšší, rozdíl doplatíte; nelze směnit za hotovost; voucher je na doručitele – kdo zná kód, může ho uplatnit, chraňte jej; po uplatnění zaniká;
  - číslo objednávky `orderRef`, kontakt pro dotazy;
  - patička: S74 wine&café, s.r.o., IČO 23035111.
- Tip pro dárce: „E-mail můžete přeposlat obdarovanému nebo vytisknout.“
- Bez externích obrázků a trackovacích pixelů (web deklaruje „žádné sledování“).

### 11.3 QR

- Obsah: `{S74_PUBLIC_BASE_URL}/staff/v/{KANONICKÝ_KÓD}` – odkaz na stránku obsluhy, **žádné heslo ani token přihlášení** (zadání).
- ZXing `QRCodeWriter`, error correction `M`, margin 2, PNG.
- Pokud QR naskenuje zákazník sám, uvidí přihlašovací stránku s textem „Jste zákazník? Voucher ukažte obsluze.“ – nic se neprozradí ani nespotřebuje.

---

## 12. Rozhraní obsluhy

Server-side Thymeleaf, mobile-first, vizuál převzít z `app.css` tohoto repozitáře (zkopírovat do `src/main/resources/static/app.css` backendu, fonty Fraunces jako woff2). Žádný framework, minimum JS. Všechny formuláře POST s CSRF tokenem, vzor PRG (POST → 303 → GET).

| Cesta | Role | Obsah |
|---|---|---|
| `GET /login`, `POST /login` | – | Formulář jméno/heslo; text pro zákazníky (kap. 11.3); chybová hláška bez rozlišení „špatné jméno/heslo“ |
| `POST /logout` | přihlášený | Odhlášení (tlačítko na každé stránce – důležité na sdíleném tabletu) |
| `GET /staff` | STAFF, MANAGER | Velké pole „Kód voucheru“ (`inputmode="text"`, `autocapitalize="characters"`, `autocomplete="off"`), tlačítko „Ověřit“; tip: „QR načtěte fotoaparátem telefonu“ |
| `POST /staff/lookup` | STAFF, MANAGER | Normalizace → 303 na `/staff/v/{kód}`; nenalezeno → zpět s chybou „Kód nenalezen. Zkontrolujte překlep a zkuste znovu.“ (počítá se do limitu, audit `LOOKUP_NOT_FOUND`) |
| `GET /staff/v/{kód}` | STAFF, MANAGER | Detail (kap. 12.1). **Pouhé zobrazení voucher nespotřebuje.** Audit `VOUCHER_VIEWED` |
| `POST /staff/v/{kód}/redeem` | STAFF, MANAGER | Uplatnění (kap. 12.2) |

QR flow: fotoaparát telefonu otevře `/staff/v/{kód}` → nepřihlášený je přesměrován na `/login` → po přihlášení Spring Security vrátí na uloženou adresu → rovnou detail voucheru. Session cookie `SameSite=Lax` zajistí, že přihlášená obsluha přejde z fotoaparátu rovnou na detail.

### 12.1 Detail voucheru

- Hodnota (velká), efektivní stav jako barevný štítek: **Platný** (zelená), **Uplatněn** (šedá + kdy a kdo), **Vypršel** (oranžová + datum), **Zrušen** (červená + důvod).
- Platí do, vystaveno, maskovaný kód (`****-****-AB12`).
- Pro nevalidní stav text: „Voucher nelze uplatnit. V případě sporu předejte věc vedoucímu.“ (zadání).
- Pro platný voucher formulář uplatnění:
  - povinné zaškrtnutí: „Zákazníka jsem upozornil(a), že je-li útrata nižší než 1 000 Kč, nevyužitý rozdíl propadá.“
  - volitelné pole „Číslo účtenky / poznámka“ (max 255);
  - tlačítko „Uplatnit voucher 1 000 Kč“.

### 12.2 Uplatnění – transakce

```sql
UPDATE voucher
   SET status = 'REDEEMED', redeemed_at = now(), redeemed_by = :staffId,
       redemption_note = :note, version = version + 1
 WHERE id = :id AND status = 'ACTIVE' AND valid_until >= :todayPrague
```

- V jedné transakci s auditem `VOUCHER_REDEEMED`. Počet změněných řádků:
  - `1` → 303 na potvrzení: velké zelené „UPLATNĚNO – odečtěte 1 000 Kč v pokladně“, čas, jméno obsluhy. Tato obrazovka se zobrazí i po refreshi (GET detailu ukáže „Uplatněn právě teď vámi“).
  - `0` → znovu načíst voucher a zobrazit důvod (už uplatněn kdy/kým, vypršel, zrušen); audit `REDEMPTION_REJECTED`.
- Podmíněný `UPDATE` je atomický – při dvou souběžných požadavcích uspěje právě jeden (zadání). Nepoužívat „načti, zkontroluj v Javě, ulož“ bez zámku.
- Dvojklik / dvojí odeslání: druhý požadavek dostane `0` a ukáže „již uplatněn vámi před X s“ – žádná chyba navíc.

---

## 13. Funkce vedoucího

Pod `/admin/**`, jen role `MANAGER`. Každý zásah vyžaduje **důvod** (povinné pole) a zapisuje audit.

| Funkce | Popis |
|---|---|
| Hledání | `/admin/vouchers?q=` – podle kódu (normalizovaného), e-mailu (case-insensitive), `orderRef` |
| Detail | Voucher + objednávka + Stripe ID (odkaz do Dashboardu `https://dashboard.stripe.com/payments/{pi}`) + historie auditu + doručení e-mailů |
| Opětovné zaslání | Na původní nebo **opravenou** adresu (překlep zákazníka); nový `email_delivery` `RESEND`; oprava adresy se uloží do objednávky a auditu (stará → nová) |
| Zrušit voucher | `ACTIVE → CANCELLED` |
| Reaktivovat | `CANCELLED → ACTIVE` |
| Vrátit uplatnění | `REDEEMED → ACTIVE` (oprava chyby mezi uplatněním a pokladnou – zadání) |
| Prodloužit platnost | nové `valid_until` ≥ dnes |
| K řešení | `/admin/attention` – objednávky s `attention_required`, selhané e-maily; tlačítko „Vyřešeno“ (s poznámkou) |
| Účty obsluhy | `/admin/staff` – seznam, založit (jméno, role, heslo min. 12 znaků), zneplatnit/obnovit, nastavit nové heslo. Nelze zneplatnit posledního aktivního `MANAGER` ani sám sebe. Zneplatnění okamžitě ukončí relace daného uživatele (`SessionRegistry`) |
| Export | `/admin/export?month=2026-11` – CSV (UTF-8 s BOM, středník) pro účetní: prodané (datum zaplacení, orderRef, hodnota, PI), uplatněné (datum, hodnota, obsluha), zrušené/refundované, vypršelé v měsíci |

**Bootstrap prvního účtu:** při startu, pokud neexistuje žádný `MANAGER` a jsou nastavené `S74_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`, vytvořit ho a zalogovat varování „odstraňte bootstrap proměnné“. Pokud `MANAGER` existuje, proměnné ignorovat.

---

## 14. Zabezpečení

### 14.1 Autentizace a autorizace

- Spring Security `formLogin`, hesla `DelegatingPasswordEncoder` (výchozí bcrypt), uživatelé z tabulky `staff_user` (`enabled = false` → přihlášení odmítnuto).
- Autorizace: `/staff/**` → `STAFF|MANAGER`; `/admin/**` → `MANAGER`; `/api/public/**`, `/api/stripe/webhook`, `/login`, statické soubory → `permitAll`; vše ostatní `denyAll`.
- Kontroly oprávnění **na backendu** pro každou akci (zadání) – UI jen skrývá tlačítka.
- Session fixation protection (výchozí), max 3 souběžné relace na uživatele, timeout 12 h.

### 14.2 CSRF

- Zapnuté pro `/login`, `/logout`, `/staff/**`, `/admin/**` (Thymeleaf vkládá token automaticky).
- Vypnuté pro `/api/public/**` (bez ambientní autentizace – žádné cookies) a `/api/stripe/webhook` (ověřeno podpisem).

### 14.3 CORS

Jen `/api/public/**`, přesné originy z konfigurace (`https://s74.cz`, `https://www.s74.cz`; lokálně `http://localhost:8000`), `allowCredentials=false`. CORS **není** autorizace (zadání) – public API je záměrně veřejné a chráněné validací a rate limity.

### 14.4 Rate limiting

In-memory (jedna instance). IP z `X-Forwarded-For` jen od nginx na 127.0.0.1 (`forward-headers-strategy: native`, Tomcat `RemoteIpValve` trusted proxies = localhost).

| Co | Limit | Reakce |
|---|---|---|
| `POST /api/public/checkout-sessions` | 10 / h / IP | `429` |
| `GET /api/public/orders/status` | 60 / 10 min / IP | `429` |
| Neúspěšné přihlášení | 5 / 15 min / uživatelské jméno; 20 / 15 min / IP | dočasné zablokování 15 min, audit `LOGIN_LOCKED`, hláška „Příliš mnoho pokusů, zkuste to za 15 minut.“ |
| Lookup s výsledkem „nenalezeno“ | 10 / 15 min / účet obsluhy; 30 / 15 min / IP | blokace lookupu 15 min, audit `RATE_LIMITED`, zobrazit na `/admin/attention` |

Druhá vrstva: `limit_req` v nginx (admin návod).

### 14.5 Hlavičky

Pro UI obsluhy a admin: `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; form-action 'self'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` (stránky s voucherem nesmí zůstat v cache), HSTS nastaví nginx.

### 14.6 Logování a tajné údaje

- Nelogovat: hesla, Stripe klíče, webhook secret, celé voucherové kódy, celá těla webhooků, SMTP heslo. E-maily v aplikačním logu maskovat.
- nginx: pro `location /staff/v/` vypnout `access_log` (kód je v cestě); pro webhook nelogovat tělo (výchozí stav).
- Stripe ani e-mailové službě neposílat voucherový kód jinak než v samotném e-mailu (do Stripe metadata jde jen `order_id`).
- Pre-commit hook / CI kontrola na `[sr]k_(live|test)_` a `whsec_` v repozitáři.
- Neexponovat `/actuator/env` ani jiné endpointy vypisující prostředí.

---

## 15. Změny statického webu (tento repozitář)

Konfigurace API na jednom místě: `js/vouchers-config.js`

```js
window.S74_VOUCHER_API = 'https://vouchery.s74.cz'; // podle R3
```

Logika v `js/vouchers.js` (vanilla JS, bez knihoven, stejný styl jako `js/main.js`).

| Soubor | Změna |
|---|---|
| `vouchery.html` | Nabídku načíst z `GET /api/public/offers` a vykreslit karty (odkaz `checkout.html?offer=CZK_1000`). Výrazná sekce **Podmínky** (jednorázové, nevyužitý rozdíl propadá, vyšší útrata = doplatek, platnost 12 měsíců, na doručitele) – zadání vyžaduje upozornění **před nákupem**. Odstranit odkaz „Vstup pro obsluhu“ a pilulku „Systém je připraven“. Když API neodpoví: „Prodej voucherů je teď dočasně nedostupný, zkuste to prosím později.“ |
| `checkout.html` | Shrnutí z `offers`; pole e-mail + **„E-mail znovu“** (prevence překlepu – nejčastější příčina „e-mail nedorazil“); povinný checkbox: „Souhlasím s [obchodními podmínkami] a beru na vědomí, že voucher je jednorázový a nevyužitý rozdíl hodnoty propadá.“; submit → `POST /api/public/checkout-sessions` → `location.assign(checkoutUrl)`. Tlačítko během požadavku zablokovat. Parametr `cancelled=1` → hláška „Platba nebyla dokončena. Můžete to zkusit znovu.“ Chybové stavy z API česky. |
| `order-success.html` | Číst `session_id`, dotazovat `GET /api/public/orders/status` každé 2 s po dobu 30 s, pak každých 5 s do 2 min. Stavy: `PAID` → „Hotovo. Voucher jsme poslali na j***@e***.com.“; `PENDING/PROCESSING` → „Čekáme na potvrzení platby…“ (spinner); `FAILED/EXPIRED` → „Platba neproběhla.“ + odkaz zpět; `UNKNOWN`/timeout → „Potvrzení zatím nedorazilo. Voucher vám přijde e-mailem, jakmile platbu potvrdíme. Pokud do 30 minut nedorazí, napište nám s číslem objednávky.“ Samotný návrat na stránku **není** důkaz platby (zadání). |
| `staff-login.html`, `staff-redeem.html`, `voucher-detail.html` | **Smazat** – obsluha běží na backendu. |
| `obchodni-podminky-vouchery.html` | **Nová** stránka – návrh textu podmínek (verze `2026-11`): prodávající (s.r.o., IČO, adresa), předmět, cena a platba, doručení e-mailem, platnost, uplatnění (jednorázové, propadnutí rozdílu, doplatek, nelze směnit za hotovost), voucher na doručitele, ztráta/opětovné zaslání, odstoupení od smlouvy (14 dní pro spotřebitele – **ověřit s právníkem**), reklamace, kontakt. Označit jako návrh ke kontrole. |
| `soukromi.html` | Nová sekce „Nákup dárkových voucherů“: jaké údaje (e-mail, údaje o objednávce a platbě; údaje karty zpracovává jen Stripe), účel (plnění smlouvy, účetní povinnosti), příjemci (Stripe Payments Europe, e-mailová služba, DigitalOcean jako hosting), doba uložení, práva. Aktualizovat datum. |
| `data/content.json`, `index.html` / `js/main.js` | Přidat položku menu „Vouchery“ → `vouchery.html` (zjistit, jak `main.js` menu vykresluje – přidat klíč do `hlavicka.menu` a odpovídající odkaz). Volitelně tlačítko v hero sekci „Darovat voucher“. |
| `sitemap.xml` | Přidat `obchodni-podminky-vouchery.html`. |
| `.github/workflows/deploy-pages.yml` | Bez změny – kopíruje `*.html` i `js/`; `fonts/` už publikuje (viz 1.1). |

Frontend nesmí obsahovat žádný Stripe klíč (ani publishable – hostovaný Checkout ho nepotřebuje).

---

## 16. Provoz a nasazení

Artefakty v repozitáři `s74-vouchers`, adresář `deploy/`:

- `deploy/nginx-s74-vouchers.conf` – server blok pro `{BACKEND}`: TLS (certbot), HSTS, `proxy_pass http://127.0.0.1:8080`, `X-Forwarded-For/Proto`, `client_max_body_size 1m`, `limit_req` zóny pro `/login` a `/api/public/`, `access_log off` pro `/staff/v/`, `location /actuator { deny all; }`.
- `deploy/s74-vouchers.service` – systemd: uživatel `s74vouchers`, `EnvironmentFile=/etc/s74-vouchers/env`, `ExecStart=/usr/bin/java -Xms128m -Xmx384m -jar /opt/s74-vouchers/app.jar`, `Restart=on-failure`, hardening (`NoNewPrivileges`, `ProtectSystem=strict`, `ReadWritePaths=/var/log/s74-vouchers`).
- `deploy/deploy.sh` – `mvn -B verify package` → `scp` jaru → atomické přepnutí symlinku → `systemctl restart` → kontrola `curl -fsS http://127.0.0.1:8080/actuator/health` (spouští se ze stroje vývojáře přes SSH).
- `deploy/backup.sh` – `pg_dump -Fc` denně, 30 denních kopií, šifrování (`age`/`gpg`), kopie mimo droplet.
- `README.md` – lokální běh, testy, Stripe CLI, nasazení, obnova ze zálohy.

Lokální vývoj: `docker compose` s PostgreSQL a Mailpit (SMTP 1025, web 8025); `stripe listen --forward-to localhost:8080/api/stripe/webhook` (vypíše `whsec_…` pro lokální `.env`).

Podrobný postup pro admina: [vouchery-admin-navod.md](vouchery-admin-navod.md), kap. 4.

---

## 17. Testování

### 17.1 Automatické testy (`mvn verify`, Testcontainers PostgreSQL)

| Oblast | Test |
|---|---|
| Kód | generátor: délka, abeceda, 100 000 kódů bez kolize; normalizace: mezery, malá písmena, pomlčky, `O/I/L`, neplatné znaky |
| Checkout | neplatná nabídka / e-mail / nesouhlas → 400; cena z ceníku ignoruje cokoliv v requestu; Stripe klient mockovaný (rozhraní `CheckoutGateway`), ověřit parametry včetně **absence `payment_method_types`** |
| Webhook | neplatný podpis → 400; platný podpis (hlavička spočítaná v testu HMAC-SHA256 nad `t.payload` testovacím secretem) → 200; **duplicitní událost nevytvoří druhý voucher**; `completed` s `unpaid` → `PAYMENT_PROCESSING`, pak `async_payment_succeeded` → voucher; `async_payment_failed` → `FAILED`; `expired` → `EXPIRED`; nesoulad částky → žádný voucher + attention |
| Souběh | webhook a status endpoint současně (2 vlákna, `CountDownLatch`) → 1 voucher; **dvě souběžná uplatnění → přesně jedno uspěje** |
| Uplatnění | aktivní → OK; uplatněný / vypršelý (fixní `Clock`) / zrušený → zamítnuto s důvodem; bez zaškrtnutí upozornění → chyba |
| E-mail | Mailpit/GreenMail: obsah (kód, QR příloha, platnost, podmínky); selhání SMTP → retry s backoffem → po 5 pokusech `FAILED` + attention; resend na novou adresu |
| Refund / spor | plný refund aktivního → `CANCELLED`; refund použitého → attention; částečný → attention; dispute created/closed |
| Security | `/admin/**` pro `STAFF` → 403; nepřihlášený `/staff/v/X` → redirect `/login` a po přihlášení zpět; CSRF bez tokenu → 403; zneplatněný účet se nepřihlásí; lockout po 5 pokusech; limit lookupů |
| CORS | povolený origin dostane hlavičky, cizí ne |

### 17.2 Ruční E2E ve Stripe sandboxu (před spuštěním)

Mapování na tabulku chybových scénářů ze zadání:

| Scénář zadání | Jak otestovat |
|---|---|
| Platba selže / opuštěna | Karta `4000 0000 0000 0002` (zamítnutí); zavřít Checkout a počkat na expiraci (nebo `stripe trigger checkout.session.expired`) → žádný voucher, nový nákup funguje |
| Zavření stránky po zaplacení | Zaplatit `4242 4242 4242 4242`, zavřít okno před návratem → voucher a e-mail přijdou |
| E-mail nedorazí | Vypnout SMTP (špatné heslo), nákup → po pokusech `FAILED` na `/admin/attention` → opravit, resend |
| Překlep v kódu | Zadat s mezerami/malými písmeny → najde; jeden znak špatně → „nenalezeno“ |
| Použitý / vypršelý / zrušený | Uplatnit dvakrát; prodloužit/zkrátit platnost v DB testovacího prostředí; zrušit přes admin |
| Souběh | Dva telefony, stejný kód, potvrdit současně |
| 3D Secure | Karta `4000 0027 6000 3184` |
| Vrácení platby | Refund v Dashboardu → voucher `CANCELLED` |
| QR | Naskenovat QR z e-mailu iPhonem i Androidem, nepřihlášený i přihlášený |

---

## 18. Implementační fáze s akceptačními kritérii

Každá fáze = samostatný PR v příslušném repozitáři, zelené `mvn verify`, aktualizovaný README.

### F0 – Repozitář a kostra (`s74-vouchers`)
- Obnovit scaffold z `6eaf87e` (kap. 1.2), aktualizovat Spring Boot na aktuální 4.x, `stripe-java` na 34.0.0, přidat thymeleaf, zxing, bucket4j, testcontainers; odstranit H2.
- `docker-compose.yml` (Postgres, Mailpit), `.env.example`, `.gitignore`, GitHub Actions `mvn -B verify`, kontrola tajných klíčů.
- **AK:** `mvn verify` projde v CI; aplikace lokálně nastartuje proti docker Postgresu; bez Stripe klíče nenastartuje se srozumitelnou chybou.

### F1 – Datový model a voucherový kód
- `V1__init.sql`, JPA entity, repozitáře, `VoucherCodeGenerator`, `VoucherCodes.normalize/format/mask`, `AuditService`, `Clock` bean (Europe/Prague).
- **AK:** testy z 17.1 „Kód“; Flyway migrace na čistou DB; `ddl-auto: validate` projde.

### F2 – Nabídka a vytvoření platby
- `OfferProperties`, `GET /api/public/offers`, `POST /api/public/checkout-sessions`, `CheckoutGateway` (Stripe implementace + testovací mock), CORS, rate limit, jednotný error handler.
- **AK:** se sandbox klíčem vrátí funkční `checkoutUrl`, Checkout ukazuje češtinu, správnou částku a text o propadnutí; objednávka v DB `PENDING_PAYMENT` se session ID.

### F3 – Webhook a vystavení voucheru
- `StripeWebhookController`, `stripe_event`, `FulfillmentService`, `GET /api/public/orders/status`, refund/dispute handlery.
- **AK:** `stripe listen` + nákup testovací kartou → přesně 1 voucher; `stripe events resend <id>` nevytvoří druhý; všechny testy „Webhook“, „Souběh“, „Refund“ zelené.

### F4 – E-mail a QR
- Outbox job, Thymeleaf šablony (HTML + text), QR generátor, retry/backoff.
- **AK:** v Mailpitu přijde e-mail dle 11.2 s funkčním QR (naskenovaný otevře `/staff/v/…`); simulované selhání SMTP → retry → `FAILED`.

### F5 – Přihlášení a obsluha
- Security konfigurace, login/logout, lockout, `/staff`, lookup, detail, uplatnění, bootstrap účtu, `app.css`.
- **AK:** kompletní flow na mobilu (Safari iOS, Chrome Android): QR → login → detail → uplatnění → potvrzení; souběžné uplatnění = 1 úspěch; testy „Security“ a „Uplatnění“ zelené.

### F6 – Vedoucí
- `/admin` hledání, detail s historií, resend (i na novou adresu), zrušit/reaktivovat/vrátit uplatnění/prodloužit, `/admin/attention`, `/admin/staff`, CSV export.
- **AK:** každý zásah vyžaduje důvod a je v auditu; `STAFF` nemá přístup; zneplatnění účtu odhlásí jeho relace.

### F7 – Statický web (`David-S74`)
- Změny z kap. 15 na feature branchi, PR do `main`.
- **AK:** lokálně (`python3 -m http.server 8000`) proti lokálnímu backendu projde nákup od `vouchery.html` po `order-success.html` se stavem `PAID`; prototypy obsluhy smazány; texty česky s diakritikou; stránka funguje na šířce 360 px.

### F8 – Nasazení
- `deploy/` artefakty dle kap. 16, runbook v README.
- **AK:** admin podle návodu nasadí na droplet; `https://{BACKEND}/login` má platný certifikát; `/actuator` zvenku vrací 403/404; port 8080 a 5432 zvenku nedostupné; záloha se vytvoří a obnoví do testovací DB.

### F9 – Akceptace a ostrý provoz
- Celá tabulka 17.2 v sandboxu na produkčním serveru (`STRIPE_LIVEMODE=false`), pak přepnutí na live klíče dle admin návodu kap. 8.
- **AK:** reálný nákup nejnižšího voucheru vlastní kartou, uplatnění, refund → voucher `CANCELLED`.

---

## 19. Mimo rozsah první verze

- Propojení s pokladnou (zadání: ruční odečet).
- Částečné čerpání / zůstatek voucheru.
- PDF voucher ke stažení / v příloze, tisková šablona (R12).
- Vlastní text věnování a jméno obdarovaného, odeslání přímo obdarovanému, odložené odeslání k datu.
- Čtečka QR v prohlížeči (stačí fotoaparát telefonu).
- Faktury přes Stripe Invoicing (pokud je účetní nevyžaduje).
- Statistiky a dashboard, newsletter.

---

## 20. Rizika a otevřené body

| Riziko | Dopad | Opatření |
|---|---|---|
| DPH u voucherů | Voucher použitelný na zboží s různou sazbou (káva/jídlo × alkohol) je pravděpodobně **víceúčelový poukaz** – DPH se odvádí až při uplatnění, ne při prodeji. Chybné zaúčtování. | Konzultovat s účetní **před spuštěním**; CSV export (F6) poskytuje data o uplatnění. Nezapínat Stripe Tax. |
| Právo na odstoupení (14 dní) a platnost | Spotřebitel může mít právo odstoupit; příliš krátká platnost může být sporná. | Obchodní podmínky zkontroluje právník; refund se dělá v Dashboardu a backend voucher zneplatní. |
| Paměť dropletu | WordPress + DB + Java + Postgres na 1 GB RAM nestačí. | Admin ověří `free -h`; ≥ 2 GB nebo swap; `-Xmx384m`. |
| Změna domény backendu | Vytištěné QR přestanou fungovat. | Zvolit stabilní doménu (R3); ruční kód funguje vždy. |
| Doručitelnost e-mailů | Vouchery ve spamu. | Ověřená doména (SPF, DKIM, DMARC), test na Seznam/Gmail/Outlook, resend z adminu. |
| Basic Auth vs. formulář | Odchylka od doslovného zadání. | Zdůvodněno v R5; zadání náhradu předjímá. |
| Neshoda API verze webhooku a SDK | Java SDK nedeserializuje objekt události. | Admin nastaví endpoint na stejnou API verzi jako SDK; kód má fallback přes `retrieve`. |
| Výpadek | Obsluha nemůže ověřit voucher. | Provozní postup v admin návodu (kap. 6.3): bez ověření neuplatňovat. |

---

## 21. Pravidla pro implementující LLM

1. **Žádné tajné údaje v repozitáři** – ani testovací klíče. Jen `.env.example` s prázdnými hodnotami.
2. Nikdy `payment_method_types`, nikdy globální `Stripe.apiKey`, vždy instance `StripeClient`, vždy ověřit podpis webhooku.
3. Voucher vzniká výhradně ve `FulfillmentService` po ověřeném `payment_status == paid`. Návratová stránka ani frontend voucher nikdy nevystaví.
4. Peníze v haléřích (`long`), nikdy `double`. Čas přes injektovaný `Clock`, data platnosti v Europe/Prague.
5. Uplatnění jen podmíněným `UPDATE … WHERE status='ACTIVE'` v transakci.
6. Celý voucherový kód nikdy do logu, auditu, Stripe metadat ani URL query stringu.
7. Všechny texty pro uživatele česky se správnou diakritikou; kód, identifikátory a commit messages anglicky (konvence repozitáře: Conventional Commits, např. `feat(vouchers): …`).
8. Každá fáze končí zelenými testy a aktualizovaným README; neimplementovat věci z kap. 19.
9. Narazíš-li na rozpor se zadáním nebo na nové rozhodnutí, zapiš ho do kap. 2 tohoto dokumentu a pokračuj výchozí volbou.
10. Po změnách v tomto repozitáři spustit `graphify update .` (pokyn z `CLAUDE.md`).
