# Vouchery pro více podniků (Stripe Connect) – rozhodovací dokument

> **Stav (8. 11. 2026):** varianta C je implementovaná v backendu (větev `claude/practical-hamilton-aox8eq`), ověřená testy
> a lokálně proti atrapě Stripe – viz kap. 9. Se skutečným Stripe Connect sandboxem zatím neověřeno (chyběl klíč).
> Mimo kód (kap. 5: Stripe schválení, smlouvy, GDPR, DPH) zůstává otevřené.
> Datum: 2026-11-08. Údaje o Stripe Connect (dostupnost pro ČR, poplatky, schválení platformy) je nutné ověřit v Dashboardu a na stripe.com/pricing.

## 1. Cíl

Nabízet systém dárkových voucherů dalším podnikům (kavárny, bary, restaurace, služby), aniž by bylo nutné pro každého klienta zvlášť
integrovat jeho Stripe účet a spravovat jeho API klíče. Každý podnik prodává vouchery pod svou značkou, peníze dostává přímo on,
provozovatel systému (platforma) dodává software.

## 2. Varianty

| | A. Instance na klienta | B. Multi-tenant bez Connect | C. Multi-tenant se Stripe Connect |
|---|---|---|---|
| Princip | Kopie současného Docker stacku pro každého klienta (vlastní DB, `.env`, branding) | Jedna aplikace, klienti v DB, každý klient vloží svůj (restricted) API klíč | Jedna aplikace, klienti v DB, klient připojí svůj Stripe účet k platformě |
| Úpravy kódu | Téměř žádné (branding, texty) | Tenant model + šifrované uložení klíčů | Tenant model + Connect onboarding + Connect webhook |
| Stripe klíče klientů | Ano, v `.env` každé instance | Ano, v DB (šifrovaně) – riziko a starost | **Ne** – jen platformní klíč |
| Onboarding klienta | Ruční (klient vytvoří klíč, webhook) | Polo-ruční | Hostovaný formulář Stripe, bez technických kroků klienta |
| Provize platformy | Jen fakturou mimo Stripe | Jen fakturou mimo Stripe | `application_fee_amount` u platby nebo paušál |
| Provoz | Roste lineárně s počtem klientů (droplet / RAM na instanci) | Jedna instance | Jedna instance |
| Vhodné pro | 1–3 klienty, pilot | Nedoporučeno | 3+ klientů, produkt |

**Doporučení:** fázově **A → C**. Variantu B přeskočit (držení cizích tajných klíčů je zbytečné riziko, když Connect řeší totéž lépe).

## 3. Doporučené nastavení Stripe Connect (varianta C)

Obchodní model odpovídá „SaaS platformě s platbami“: podnik prodává vlastní vouchery vlastním zákazníkům, platforma dodává software.

- **Přímé platby (direct charges):** Checkout Session se vytváří na účtu klienta. **Klient je prodávající (merchant of record)**:
  peníze jdou rovnou jemu, refundy a spory (chargebacky) jdou za ním, na výpisu karty je jeho název.
- **Účty přes Accounts v2** (`POST /v2/core/accounts`), ne zastaralé typy `standard` / `express` / `custom`:
  - `dashboard: "full"` – klient má plný Stripe Dashboard,
  - `defaults.responsibilities.fees_collector: "stripe"` – poplatky Stripe platí klient,
  - `defaults.responsibilities.losses_collector: "stripe"` – záporné zůstatky nese Stripe, ne platforma,
  - `configuration.merchant` s capability `card_payments`.
- **Onboarding:** hostovaný (account link nebo embedded komponenta `account_onboarding`), k tomu komponenta `notification_banner`,
  aby klient viděl nové požadavky Stripe. Vlastní sběr údajů o firmě a osobách nestavět.
- **Před ostrými platbami** kontrolovat `configuration.merchant.capabilities.card_payments.status === "active"` a sledovat změny přes
  webhooky účtu.
- **Výnos platformy:** `application_fee_amount` (pevná částka nebo procento z voucheru) nebo měsíční paušál fakturovaný mimo platbu.
  Při nízké provizi (< ~4 %) počítat s tím, že poplatky Stripe nese klient.

**Proč ne „platforma prodává za klienty“ (destination charges):** platforma by byla prodávajícím, ručila by za spory a de facto by
vybírala peníze za služby, které poskytne někdo jiný (a za vouchery, které nemusí být nikdy uplatněny). Pro vouchery je to právně
i finančně horší varianta.

## 4. Změny v současném kódu (varianta C)

Výchozí stav: `backend/` (Spring Boot, PostgreSQL), statické stránky v kořeni webu S74, konfigurace v `application.yml` / `.env`.

### 4.1 Datový model

- Nová tabulka `tenant`: `id`, `slug`, název, `stripe_account_id`, stav onboardingu, `livemode`, branding (logo, barvy, fonty),
  doména / subdoména, odesílatel a podpis e-mailů, časová zóna, jazyk, platnost voucherů (měsíce), verze a URL podmínek, kontakt.
- Tabulka `offer` (ceník per klient) místo `s74.voucher.offers` v YAML.
- `tenant_id` ve všech doménových tabulkách (`voucher_order`, `voucher`, `email_delivery`, `audit_log`, `staff_user`, …),
  unikátní omezení doplnit o `tenant_id` (kde dává smysl).
- `stripe_event`: idempotence přes dvojici (`account`, `event_id`).
- Kód voucheru: buď globálně unikátní (jednodušší vyhledání), nebo unikátní per klient + QR obsahuje identifikaci klienta.

### 4.2 Izolace dat

- Každý dotaz filtrovaný `tenant_id` (repository vrstva + kontrola v servisech).
- Zvážit Postgres Row-Level Security jako druhou pojistku.
- Integrační testy „klient A nevidí, nenajde ani neuplatní voucher klienta B“ pro obsluhu, vedoucího, export i veřejné API.

### 4.3 Stripe

- Jeden platformní (restricted) klíč; volání za klienta přes `RequestOptions` se `stripeAccount` (hlavička `Stripe-Account`).
  Abstrakce `CheckoutGateway` zůstává, jen dostane účet klienta.
- **Connect webhook endpoint** (události z připojených účtů): klient se určí z pole `account` události; ověřování podpisu i
  zpracování (`checkout.session.completed`, `…async_payment_succeeded`, refundy, spory) zůstává, jen v kontextu klienta.
- Webhooky účtu (`v2` account events) pro změny capability → stav klienta (aktivní / pozastavený prodej).
- Testovací režim (R22) per klient: klient v testu = banner + `noindex` jen na jeho stránkách.

### 4.4 Uživatelé a role

- Role: **správce platformy** (zakládá klienty, vidí stav onboardingu, nevidí zákaznická data bez důvodu), **vedoucí klienta**,
  **obsluha klienta**.
- Přihlášení zůstává (formulář + session), uživatel patří právě jednomu klientovi; správce platformy zvlášť.
- Audit doplnit o `tenant_id` a o akce správce platformy.

### 4.5 Frontend

- Dnes: statické stránky S74 + `js/vouchers-config.js` s jednou adresou API.
- Cíl (zvolit jedno, případně obojí):
  1. **Hostované stránky** nabídky, objednávky a stavu na `klient.<domena-platformy>` nebo na subdoméně klienta (CNAME) –
     jednodušší pro klienty bez vlastního webu.
  2. **Vložitelný skript / tlačítko** pro existující weby klientů (jako dnes u S74), API dostává `tenant` ve volání.
- Branding přes CSS proměnné z `tenant` (barvy, font, logo); texty a podmínky per klient.
- CORS: povolené originy per klient z DB místo jedné hodnoty z konfigurace.
- E-mail s voucherem: šablona s brandingem klienta, odesílatel přes ověřenou doménu (SPF/DKIM/DMARC per doména nebo společná
  odesílací doména platformy s `Reply-To` klienta).

### 4.6 Provoz

- Jedna instance na 1 GB dropletu stačí pro jednotky klientů s malým provozem; při růstu 2 GB a sledování paměti.
- Zálohy a obnova beze změny (jedna DB), ale export/odstranění dat **jednoho** klienta (ukončení smlouvy, GDPR) jako nový nástroj.
- Monitoring: chyby webhooků a fronty e-mailů po klientech.

## 5. Mimo kód (rozhoduje o proveditelnosti)

- **Stripe:** dostupnost Connect pro platformu v ČR a pro klienty v jejich zemi, schválení platformy, poplatky Connect – ověřit.
- **GDPR:** klient je správce údajů zákazníků (e-mail, objednávky), platforma je zpracovatel → **smlouva o zpracování** s každým
  klientem, seznam podzpracovatelů (hosting, e-mail, Stripe), postup při úniku dat.
- **Podmínky:** šablona obchodních podmínek voucherů pro klienty (prodávající je klient) + smlouva / podmínky platformy
  (platforma ↔ klient: cena, odpovědnost, dostupnost, ukončení, export dat). Obojí zkontrolovat právníkem.
- **DPH:** jednoúčelový vs. víceúčelový voucher se zdaňuje jinak; určuje každý klient se svým účetním, systém musí umět oba
  případy evidovat (minimálně správné údaje v exportu).
- **Podnikání platformy:** právní forma, pojištění odpovědnosti, podpora a reakční doba při výpadku, fakturace provize / paušálu.
- **Konkurence:** existují hotové služby (např. Gift Up). Odlišení: čeština, lokální podpora, jednoduchost, cena, uplatnění
  přímo obsluhou v mobilu.

## 6. Plán po fázích

| Fáze | Obsah | Kdy |
|---|---|---|
| 0 | Dokončit S74: zkušební provoz (test banner), pak ostrý provoz | teď |
| 1 | Druhý klient jako **samostatná instance** (varianta A): stejný Docker stack, vlastní DB, `.env`, branding. Zapsat, co se u něj lišilo | při prvním zájemci |
| 2 | Rozhodnutí o produktu: cena, smlouvy, GDPR, ověření Connect u Stripe | po 2–3 klientech |
| 3 | Multi-tenant + Connect (kap. 4), migrace instancí z fáze 1 do jedné aplikace (klient po klientovi, včetně připojení jejich Stripe účtu přes Connect) | při 3–5 klientech |
| 4 | Samoobslužná registrace podniků, správa brandingu a ceníku v administraci | podle poptávky |

**Do té doby (bez extra práce):** nové věci nezapisovat natvrdo na S74 (texty, adresy, barvy patří do konfigurace), ceník držet
v konfiguraci, Stripe volání jen přes `CheckoutGateway`.

## 7. Odhad rozsahu

Fáze 1: dny (převážně branding, texty, server). Fáze 3: řádově **jednotky týdnů** (datový model a izolace, Connect onboarding a
webhook, role, hostované stránky / skript, testy, migrace). Přesnější odhad až po fázi 1, kdy bude jasné, co klienti skutečně
potřebují jinak.

## 8. Otevřené otázky

1. Prodávat jen software (paušál) nebo i provizi z každého voucheru?
2. Hostované stránky, vložitelný skript, nebo obojí?
3. Společná odesílací doména e-mailů, nebo vlastní doména každého klienta?
4. Má platforma vidět zákaznická data klientů (podpora), nebo jen na vyžádání a s auditem?
5. Jak řešit klienta, který skončí: export dat, platnost už prodaných voucherů, lhůty.

## 9. Stav implementace a přijatá rozhodnutí

Implementováno podle kap. 3 a 4 (kód v `backend/`, migrace `V2__tenants_and_connect.sql`). Otevřené otázky z kap. 8 a další
volby byly rozhodnuty takto (vše jde později změnit):

| # | Otázka | Rozhodnutí v implementaci |
|---|---|---|
| D1 | Paušál nebo provize (8.1) | Obojí možné: provize per podnik (`application_fee` = pevná částka + %), výchozí 0 → paušál fakturovaný mimo Stripe. Provize max. 20 % a vždy menší než platba. |
| D2 | Hostované stránky nebo skript (8.2) | **Obojí.** Hostovaná stránka `/v/{slug}` (server-side, bez JS, bez cookies, generované vzorové podmínky) + stávající `js/vouchers.js` s `S74_VOUCHER_TENANT`. Návrat po platbě: na web podniku, pokud ho má, jinak na hostovanou stránku. |
| D3 | Odesílací doména (8.3) | Společná ověřená doména platformy (`S74_MAIL_FROM`), jméno odesílatele = název podniku, `Reply-To` = e-mail podniku. Vlastní doména podniku zatím ne. |
| D4 | Přístup platformy k zákaznickým datům (8.4) | **Ne.** Správce platformy vidí jen podniky, stav Stripe a souhrnná čísla (zaplacené objednávky, aktivní vouchery) a audit správy podniku. |
| D5 | Konec podniku (8.5) | Pozastavení (`SUSPENDED`) zastaví jen **nový prodej**; prodané vouchery obsluha dál uplatňuje. Export/smazání dat podniku jako nástroj zatím není (měsíční CSV export vedoucího funguje). |
| D6 | Výchozí podnik S74 | Zůstává na platformním účtu (`payment_mode = PLATFORM`), prázdná nastavení se berou z `application.yml` → dosavadní nasazení beze změny. Po připojení vlastního účtu ho správce přepne na `CONNECT`; staré objednávky si drží účet, na kterém vznikly (`voucher_order.stripe_account_id`), takže jejich refundy/spory dál chodí z platformy. |
| D7 | Typ účtů | Accounts v2: `dashboard=full`, `fees_collector=stripe`, `losses_collector=stripe`, `configuration.merchant.capabilities.card_payments.requested=true`, země CZ, měna CZK. Onboarding přes v2 account link (`configurations: [merchant]`), účet se zakládá s idempotency klíčem podle podniku. |
| D8 | Kdy smí podnik prodávat | `status=ACTIVE` a `card_payments=active` (u `PLATFORM` vždy) a neprázdný ceník. Stav se obnovuje: návrat z onboardingu, `account.updated` (Connect webhook), v2 thin události `v2.core.account*` (`/api/stripe/account-events`), ruční tlačítko a hodinová kontrola nedokončených účtů. `account.application.deauthorized` prodej okamžitě zastaví. |
| D9 | Webhooky | Tři endpointy s vlastními secrety: platforma, Connect, v2 události účtů. Účet z události určuje, ze kterého účtu se čte session a které objednávce smí patřit – session z jiného účtu se ignoruje (bez příznaku k řešení, aby cizí podnik nemohl zaplavit frontu). Idempotence zůstává přes globálně unikátní `event_id` (ID událostí jsou ve Stripe unikátní napříč účty); ukládá se i `account`. |
| D10 | Kód voucheru | Globálně unikátní, QR dál vede na `/staff/v/{kód}`. Obsluha najde jen vouchery svého podniku; cizí kód se chová jako neexistující a počítá se do limitu hádání. |
| D11 | Uživatelé | Uživatelské jméno globálně unikátní (přihlášení beze změny, bez pole „podnik“). Účet patří právě jednomu podniku, správce platformy žádnému (kontrola v DB). Role `PLATFORM_ADMIN` nemá přístup k `/staff` ani `/admin`. |
| D12 | Izolace dat | Filtrování `tenant_id` v repository a službách (žádný „globální“ dotaz v obsluze/vedoucím/exportu) + integrační testy izolace. Postgres RLS jako druhá pojistka zatím **ne** (aplikace používá jednoho DB uživatele; doplnit při růstu). |
| D13 | Audit | `audit_log.tenant_id` se doplní automaticky z objednávky / voucheru / účtu aktéra; události přihlášení podle uživatelského jména. |
| D14 | Ceník | Tabulka `tenant_offer` (kód `CZK_{částka}`, 50–100 000 Kč, max. 8 hodnot); odebraná hodnota se jen deaktivuje. |
| D15 | Lokální vývoj | Atrapa Stripe `backend/dev/stripe-mock` + `S74_STRIPE_API_BASE` (aplikace ho s ostrým režimem odmítne). `stripe-mock` od Stripe v2 API nepodporuje, proto vlastní atrapa. |

### Ověřeno

- `mvn verify`: 118 testů zelených (88 původních + 30 nových), 1 volitelný test proti sandboxu přeskočen.
- Lokálně (PostgreSQL, Mailpit, atrapa Stripe, Playwright): správce založí podnik a vedoucího → vedoucí projde onboardingem →
  zákazník koupí na `/v/{slug}` (bez cookie) → webhook z účtu podniku → voucher na stránce výsledku i v e-mailu (odesílatel
  „název podniku“, Reply-To podniku), provize 2,5 % uložena → obsluha podniku voucher najde, obsluha S74 ne.

### Neověřeno / zbývá

| Co | Proč / jak |
|---|---|
| Skutečný Stripe Connect sandbox | V prostředí nebyl testovací klíč. `STRIPE_SMOKE=1 mvn test -Dtest=StripeSandboxSmokeTest` (README backendu). Ověřit hlavně v2 account link (`configurations` jde jako extra parametr – SDK 34 nemá typovaný setter) a že `account.updated` chodí i pro v2 účty. |
| Dostupnost Connect pro platformu v ČR, schválení platformy, poplatky | Admin ve Stripe Dashboardu (kap. 5). |
| Smlouva o zpracování, podmínky platformy, vzorové podmínky `/v/{slug}/podminky` | Právník – text je jen návrh. |
| Export / smazání všech dat jednoho podniku | Nástroj zatím není (kap. 4.6). |
| Embedded komponenty Stripe (`account_onboarding`, `notification_banner`) | Použit hostovaný onboarding (bez JS v administraci); banner požadavků nahrazuje stav na `/admin/stripe`. |
