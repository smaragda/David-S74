# Vouchery pro více podniků (Stripe Connect) – rozhodovací dokument

> **Stav:** návrh k rozhodnutí, nic z toho není implementováno. Současné řešení je jednoklientské (S74 wine&café).
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
