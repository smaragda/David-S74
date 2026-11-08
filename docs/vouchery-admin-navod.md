# Vouchery S74 – návod pro admina

> Co musí udělat **člověk**, aby šlo vouchery naprogramovat, spustit a provozovat: Stripe, e-mail, DNS, server, účty obsluhy, právní a účetní věci, ostrý start a údržba. Technické detaily jsou v [vouchery-implementacni-analyza.md](vouchery-implementacni-analyza.md) (dále „analýza“), cíle v [vouchery-implementacni-zadani.md](vouchery-implementacni-zadani.md).
>
> Stav k 4. 11. 2026. Ovládání Dashboardů se může změnit – když položka chybí přesně tam, kde ji návod uvádí, hledej podle názvu.

## Obsah

1. Rozhodnutí před startem
2. Stripe
3. E-mailová služba a DNS
4. Server DigitalOcean
5. Účty obsluhy
6. Provozní postupy pro obsluhu
7. Právo a účetnictví
8. Spuštění ostrého provozu
9. Pravidelná údržba a incidenty
10. Více podniků (Stripe Connect) – volitelné

Pořadí: kap. 1 a 2.1–2.3 **hned** (vývoj bez nich nemůže začít), kap. 3–5 během vývoje, kap. 7 souběžně (trvá nejdéle), kap. 8 na konci.

### Zásady pro tajné údaje

- Stripe klíče, webhook secret, hesla k SMTP a databázi **nikdy** neposílej e-mailem, přes WhatsApp ani do chatu s AI. Ulož je do správce hesel (Bitwarden, 1Password…) a na server je vlož přímo do souboru `/etc/s74-vouchers/env`.
- Nikdy je necommituj do gitu.
- Když odejde člověk, který k nim měl přístup, klíče vyměň (kap. 9.3).

---

## 1. Rozhodnutí před startem

Vývoj jede s výchozími hodnotami, ale před ostrým provozem je potvrď (tabulka R1–R12 v analýze, kap. 2).

| # | Rozhodnutí | Výchozí volba | Kdo rozhoduje | Hotovo |
|---|---|---|---|---|
| 1 | Hodnoty voucherů | 500 / 1 000 / 2 000 Kč | vlastníci | ☐ |
| 2 | Platnost | 12 měsíců | vlastníci + právník | ☐ |
| 3 | Adresa backendu (QR kódy na ni budou odkazovat rok) | `vouchery.s74.cz` | ty | ☐ |
| 4 | E-mailová služba | viz kap. 3 | ty | ☐ |
| 5 | Kdo je „vedoucí“ (role MANAGER) | oba vlastníci | vlastníci | ☐ |
| 6 | Jak se voucher zadá do pokladny (typ platby „Voucher“, sleva…) | domluvit s účetní | vlastníci + účetní | ☐ |
| 7 | Náhradní postup při výpadku (kap. 6.3) | voucher neuplatnit | vlastníci | ☐ |
| 8 | Kdo vyřizuje reklamace a refundy | vlastník s přístupem do Stripe | vlastníci | ☐ |

---

## 2. Stripe

### 2.1 Kdo vlastní účet

- Ostrý účet zakládá **firma vinárny: S74 wine&café, s.r.o., IČO 23035111**, ne ty osobně. Peníze chodí na její bankovní účet a ona za účet odpovídá.
- Vlastníkem účtu ve Stripe by měl být jednatel. Ty dostaneš pozvánku do týmu s rolí **Developer** (nebo Administrator, pokud budeš spravovat i nastavení).
- Než bude ostrý účet hotový, můžeš vyvíjet v sandboxu na svém vlastním Stripe účtu (2.2). Sandbox se do ostrého provozu nepřenáší – v ostrém účtu se pak jen znovu vytvoří klíč a webhook (kap. 8).

### 2.2 Sandbox pro vývoj

Sandbox = izolované testovací prostředí Stripe s vlastními klíči a daty, peníze se nehýbou.

1. Přihlas se na `https://dashboard.stripe.com` (vlastní účet nebo účet vinárny).
2. Vlevo nahoře přepínač účtu → **Sandboxes** → **Create sandbox** → název `s74-vouchers-dev`.
   Alternativa bez registrace: `npm i -g @stripe/cli` a `stripe sandbox create` (vytvoří testovací klíče; později `stripe sandbox claim`).
3. Pro CI / automatické testy případně druhý sandbox `s74-vouchers-ci`.
4. Nainstaluj Stripe CLI a přihlas ho do sandboxu: `stripe login`. Lokální webhooky pak: `stripe listen --forward-to localhost:8080/api/stripe/webhook` – CLI vypíše `whsec_…` pro lokální `.env`.

### 2.3 Restricted API key (pro vývoj i ostrý provoz)

Aplikace nepoužívá plný tajný klíč (`sk_…`), ale **omezený klíč** (`rk_…`) jen s potřebnými právy – při úniku napáchá mnohem méně škody.

1. Dashboard (v sandboxu) → **Developers → API keys** (`/apikeys`) → **Create restricted key** → „Vytváříš klíč pro vlastní aplikaci“.
2. Název: `s74-vouchers-backend-dev`.
3. Práva (vše ostatní **None**):

   | Zdroj | Právo |
   |---|---|
   | Checkout Sessions | **Write** |
   | PaymentIntents | **Read** |
   | Charges (a Refunds) | **Read** |
   | Disputes | **Read** |

4. Ulož klíč do správce hesel. Pokud aplikace hlásí `403 … does not have the required permissions`, doplň v klíči jen to konkrétní právo, nepřecházej na `sk_`.
5. Doporučené: u klíče nastav **IP omezení** na veřejnou IP dropletu (u ostrého klíče povinně; u vývojového klíče ne, jinak nepůjde z tvého počítače).

### 2.4 Nastavení účtu (ostrý účet vinárny)

Postupně v Dashboardu ostrého účtu:

1. **Aktivace účtu** (průvodce „Activate payments“):
   - typ: společnost (s.r.o.), IČO, sídlo, DIČ (pokud je plátce DPH), obor: restaurace/bar;
   - údaje a doklad totožnosti jednatele (a vlastníků nad 25 %);
   - bankovní účet firmy v **CZK** (IBAN) pro výplaty;
   - web: `https://s74.cz`. Stripe kontroluje, že web obsahuje popis produktu s cenou, kontakt, obchodní podmínky, podmínky vrácení peněz a zásady ochrany osobních údajů → **aktivaci dokonči až po nasazení stránek z kap. 7** (nebo počítej s doplňujícím dotazem Stripe).
2. **Settings → Public details** (`/settings/public`): veřejný název `S74 wine&café`, support e-mail `s74winecafe@seznam.cz`, web, URL obchodních podmínek `https://s74.cz/obchodni-podminky-vouchery.html`, URL soukromí `https://s74.cz/soukromi.html`.
3. **Statement descriptor** (text na výpisu z karty): `S74 WINE CAFE` (5–22 znaků, bez diakritiky), zkrácený `S74`. Zákazník tak platbu na výpisu pozná – méně sporů.
4. **Branding** (`/settings/branding`): logo (`images/logo.jpg`), ikona, barva tlačítka `#b1442f` (akcent webu), pozadí `#f8f1e7`. Projeví se na platební stránce Checkout.
5. **Payment methods** (`/settings/payment_methods`): zapnout **Cards, Apple Pay, Google Pay, Link**. Metody s **odloženým potvrzením** (SEPA Direct Debit, bankovní převody apod.) **vypnout** – voucher by přišel až za několik dní. Aplikace je umí správně obsloužit, ale pro dárky to nedává smysl. Kód neurčuje metody natvrdo, řídí se tímto nastavením.
6. **Customer emails** (`/settings/emails`): zapnout **Successful payments** – Stripe pošle zákazníkovi potvrzení o platbě (voucher posílá naše aplikace zvlášť). Pro refundy také zapnout.
7. **Payouts** (`/settings/payouts`): automatické výplaty, denně nebo týdně. První výplata nového účtu trvá obvykle déle (cca 7 dní).
8. **Team** (`/settings/team`): pozvi sebe (Developer) a druhého vlastníka (Administrator). Brigádník přístup do Stripe **nepotřebuje**.
9. **Zabezpečení**: každý člen týmu zapne 2FA přes **passkey nebo autentizační aplikaci**, ne SMS.
10. **Poplatky**: ověř aktuální ceník na `https://stripe.com/cz/pricing` (karty z EHP jsou nejlevnější). Při refundu Stripe poplatek za původní platbu **nevrací**.
11. **Stripe Tax nezapínat** – DPH u voucherů řeší účetní (kap. 7).

### 2.5 Webhook endpoint

Stripe posílá aplikaci zprávy o platbách. **Bez webhooku systém nefunguje** – je to jediný spolehlivý zdroj informace, že bylo zaplaceno.

Vytváří se zvlášť v sandboxu (pro testovací server) a zvlášť v ostrém režimu (kap. 8):

1. Dashboard → **Developers → Webhooks** (v novějším rozhraní **Workbench → Webhooks / Event destinations**) → **Add endpoint / Add destination**.
2. URL: `https://vouchery.s74.cz/api/stripe/webhook` (podle rozhodnutí 1.3).
3. **API version**: stejná, jakou používá Java SDK v aplikaci (analýza kap. 4: `2026-09-30.endive` pro `stripe-java` 34.0.0). Když se verze liší, aplikace nedokáže přečíst obsah zpráv. Při pozdějším upgradu SDK se mění i tady.
4. Události (Select events):
   - `checkout.session.completed`
   - `checkout.session.async_payment_succeeded`
   - `checkout.session.async_payment_failed`
   - `checkout.session.expired`
   - `charge.refunded`
   - `charge.dispute.created`
   - `charge.dispute.closed`
5. Ulož → zobraz **Signing secret** (`whsec_…`) → do správce hesel a na server do `STRIPE_WEBHOOK_SECRET`.
6. Kontrola: v detailu endpointu záložka s doručenými událostmi – po testovacím nákupu musí být odpovědi `200`. Opakovaně selhávající endpoint Stripe po čase vypne a pošle upozornění e-mailem – ty e-maily nepřehlížej.

### 2.6 Testovací platby

V sandboxu se platí testovacími kartami (libovolné budoucí datum, libovolné CVC):

| Karta | Výsledek |
|---|---|
| `4242 4242 4242 4242` | úspěch |
| `4000 0027 6000 3184` | vyžaduje 3D Secure |
| `4000 0000 0000 0002` | zamítnuto |
| `4000 0000 0000 9995` | nedostatek prostředků |

Celý testovací scénář je v analýze, kap. 17.2.

### 2.7 Kde co ve Stripe najdeš

- **Payments** – všechny platby; u platby je v popisu `Voucher S74 {číslo objednávky}` a v metadatech `order_id`.
- Z adminu aplikace (`/admin/vouchers/…`) vede odkaz přímo na platbu ve Stripe.
- **Balances / Payouts** – kolik a kdy přišlo na účet. Pro účetní: **Reports** → měsíční výkaz plateb a výplat (CSV).

### 2.8 Refund (vrácení peněz)

1. Nejdřív v adminu aplikace zkontroluj stav voucheru (`/admin/vouchers?q=…`).
2. Stripe → Payments → platba → **Refund** → plná částka → důvod.
3. Aplikace přes webhook voucher automaticky zneplatní (stav **Zrušen**). Pokud už byl uplatněn, objeví se na `/admin/attention` k individuálnímu řešení (zadání).
4. Částečný refund voucher nezneplatní – jen ho označí k řešení.

### 2.9 Spor (chargeback)

Zákazník může platbu rozporovat u své banky. Stripe pošle e-mail a platba se objeví v **Disputes**.

- Aplikace voucher automaticky zablokuje (Zrušen) a označí k řešení.
- Ve Stripe do termínu nahraj důkazy: potvrzení o odeslání voucheru (datum, adresa – z adminu), stav voucheru (neuplatněn / uplatněn kdy), obchodní podmínky, které zákazník odsouhlasil (verze a čas souhlasu jsou v detailu objednávky).
- Spor vyhrán → v adminu rozhodni o reaktivaci voucheru. Prohrán → peníze jsou zpět u zákazníka, voucher zůstává zrušen.

---

## 3. E-mailová služba a DNS

### 3.1 Výchozí stav

- Doména `s74.cz` má DNS u **Forpsi**. Web běží na GitHub Pages (A záznamy `185.199.108–111.153`, `www` → `smaragda.github.io`). **Tyto záznamy neměň.**
- Doména nemá žádné e-mailové záznamy (MX, SPF, DKIM, DMARC). Kontakt vinárny je `s74winecafe@seznam.cz`.
- Odesílat vouchery přes bezplatnou schránku Seznam nedoporučuji: je určená pro osobní poštu, má limity a hromadné automatické odesílání z aplikace není její účel. Vlastní poštovní server také ne (zadání).

### 3.2 Volba služby

Doporučení: **transakční e-mailová služba**, odesílatel `vouchery@s74.cz`, odpovědi (Reply-To) půjdou na `s74winecafe@seznam.cz`. Objem je malý (desítky e-mailů měsíčně), stačí bezplatný nebo nejnižší tarif.

| Služba | Poznámka |
|---|---|
| **Brevo** | Firma v EU (Francie), bezplatný tarif s denním limitem, SMTP. Dobrá výchozí volba kvůli GDPR. |
| **Postmark** | Výborná doručitelnost transakčních e-mailů, placený od nízkých objemů, SMTP. |
| **Amazon SES** (region `eu-central-1`) | Nejlevnější, ale složitější nastavení (vyžádání produkčního režimu). |

Ceny a limity ověř na webu služby v den registrace. Aplikace mluví obecným SMTP – službu lze později vyměnit bez programování.

### 3.3 Postup

1. Zaregistruj účet služby na firmu vinárny (fakturační údaje s.r.o.), zapni 2FA.
2. Přidej odesílací doménu `s74.cz` (u některých služeb „Sender domain“ / „Domain authentication“).
3. Služba vypíše DNS záznamy. Přihlas se do **administrace Forpsi → Domény → s74.cz → DNS záznamy** a přidej:

   | Typ | Název | Hodnota | Účel |
   |---|---|---|---|
   | TXT | `@` (s74.cz) | `v=spf1 include:<podle služby> ~all` | SPF – kdo smí za doménu posílat. **Na doméně smí být jen jeden SPF záznam.** |
   | TXT nebo CNAME | `<selektor>._domainkey` | podle služby | DKIM – podpis e-mailů |
   | CNAME | podle služby (např. `bounces`, `pm-bounces`) | podle služby | Return-Path / bounce (jen pokud služba vyžaduje) |
   | TXT | `_dmarc` | `v=DMARC1; p=none; adkim=s; aspf=r` | DMARC – začni `p=none`, po měsíci bez problémů změň na `p=quarantine` |

4. Ve službě klikni na ověření domény (DNS se může projevit za minuty až hodiny). Kontrola z terminálu: `dig +short TXT s74.cz`, `dig +short TXT _dmarc.s74.cz`.
5. Vytvoř **SMTP přihlašovací údaje** (ne heslo k účtu – samostatný SMTP klíč) → do správce hesel → na server: `SMTP_HOST`, `SMTP_PORT` (obvykle 587), `SMTP_USERNAME`, `SMTP_PASSWORD`.
6. Test doručení: pošli testovací voucher na adresy **Seznam, Gmail a Outlook/Hotmail**. Zkontroluj, že nespadl do spamu a že v hlavičkách je `spf=pass`, `dkim=pass`, `dmarc=pass` (v Gmailu „Zobrazit originál“).

### 3.4 Subdoména backendu

Pokud platí rozhodnutí 1.3 (`vouchery.s74.cz`): ve Forpsi DNS přidej

| Typ | Název | Hodnota | TTL |
|---|---|---|---|
| A | `vouchery` | veřejná IPv4 dropletu | 3600 |
| AAAA | `vouchery` | IPv6 dropletu (jen pokud ji droplet má) | 3600 |

Kontrola: `dig +short vouchery.s74.cz` vrátí IP dropletu. Teprve potom jde vystavit HTTPS certifikát (kap. 4.5).

Když místo toho použiješ současnou doménu serveru, nic v DNS neměň – jen ji zadej do `S74_PUBLIC_BASE_URL` a do webhooku. **Doménu pak už neměň**, jinak přestanou fungovat QR kódy na rozdaných voucherech.

---

## 4. Server DigitalOcean

> **Aktualizace (R19):** backend poběží na **novém samostatném dropletu s 1 GB RAM** a v **Dockeru** (PostgreSQL + aplikace). Postup serveru
> (aktualizace, swap, firewall, Docker, nginx, certbot, nasazení, zálohy) je krok za krokem v `backend/deploy/README.md` a nahrazuje
> kap. 4.1–4.7 níže, která popisují původní variantu s nativní Javou a sdíleným serverem (zachováno jako podklad k bezpečnostním zásadám).
> Zásady z kap. 4.2 (snapshot, SSH jen klíčem, firewall, porty 8080/5432 zavřené) platí dál.

Příkazy jsou pro Ubuntu/Debian. Pokud server běží na něčem jiném nebo místo nginx používá Apache, postup je obdobný – dej vědět implementujícímu LLM, ať upraví `deploy/` soubory.

### 4.1 Zjisti stav (před vývojem F8)

```bash
lsb_release -a                     # verze OS – musí být podporovaná (Ubuntu 22.04/24.04 LTS)
free -h                            # RAM – pro WordPress + Javu + Postgres aspoň 2 GB
df -h /                            # volné místo – aspoň 5 GB
systemctl list-units --type=service --state=running | grep -E 'nginx|apache|mysql|mariadb|postgres|php'
ss -tlnp                           # co poslouchá na kterých portech
ufw status verbose                 # firewall
```

Výsledek pošli implementujícímu LLM (bez hesel). Pokud má droplet 1 GB RAM, buď ho v DigitalOcean zvětši (**Resize**, jen CPU/RAM – lze vrátit), nebo přidej swap 2 GB.

### 4.2 Aktualizace a zabezpečení (zadání: „Aktualizovat server i WordPress“)

1. **Snapshot** dropletu v DigitalOcean (Droplet → Backups/Snapshots → Take snapshot) – před jakoukoliv změnou.
2. `sudo apt update && sudo apt full-upgrade`, restart, pokud to systém žádá.
3. Zapni automatické bezpečnostní aktualizace: `sudo apt install unattended-upgrades && sudo dpkg-reconfigure unattended-upgrades`.
4. WordPress: v `wp-admin` aktualizuj jádro, pluginy a šablony; nepoužívané pluginy smaž. Zkontroluj, že admin účty WordPressu mají silná hesla a nikdo navíc tam není.
5. SSH jen klíčem: v `/etc/ssh/sshd_config` `PasswordAuthentication no`, `PermitRootLogin prohibit-password` (nejdřív ověř, že se klíčem přihlásíš!).
6. Firewall: povolit jen `22`, `80`, `443`. **Porty 8080 (aplikace) a 5432 (Postgres) nesmí být zvenku dostupné.**
   ```bash
   sudo ufw allow OpenSSH && sudo ufw allow 'Nginx Full' && sudo ufw enable
   ```
   Alternativně DigitalOcean **Cloud Firewall** se stejnými pravidly.
7. Zapni **Droplet Backups** (týdenní zálohy celého serveru, za příplatek) – doplněk k zálohám databáze (4.7).

### 4.3 Java a PostgreSQL

```bash
sudo apt install openjdk-21-jre-headless postgresql
java -version
sudo -u postgres createuser --pwprompt s74_vouchers      # heslo: openssl rand -base64 24 → správce hesel
sudo -u postgres createdb --owner=s74_vouchers s74_vouchers
```

PostgreSQL poslouchá jen na localhostu (výchozí stav – ověř v `ss -tlnp`, že `5432` je na `127.0.0.1`).

### 4.4 Systémový uživatel a tajné údaje

```bash
sudo useradd --system --home /opt/s74-vouchers --shell /usr/sbin/nologin s74vouchers
sudo mkdir -p /opt/s74-vouchers /etc/s74-vouchers /var/log/s74-vouchers
sudo chown s74vouchers: /opt/s74-vouchers /var/log/s74-vouchers
sudo touch /etc/s74-vouchers/env
sudo chown root:s74vouchers /etc/s74-vouchers/env && sudo chmod 640 /etc/s74-vouchers/env
sudo nano /etc/s74-vouchers/env
```

Obsah (hodnoty ze správce hesel; jména proměnných viz analýza kap. 8):

```
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:postgresql://localhost:5432/s74_vouchers
DATABASE_USERNAME=s74_vouchers
DATABASE_PASSWORD=…
STRIPE_API_KEY=rk_test_…
STRIPE_WEBHOOK_SECRET=whsec_…
STRIPE_LIVEMODE=false
S74_PUBLIC_BASE_URL=https://vouchery.s74.cz
S74_FRONTEND_BASE_URL=https://s74.cz
SMTP_HOST=…
SMTP_PORT=587
SMTP_USERNAME=…
SMTP_PASSWORD=…
S74_MAIL_FROM=S74 wine&café <vouchery@s74.cz>
S74_BOOTSTRAP_ADMIN_USERNAME=…
S74_BOOTSTRAP_ADMIN_PASSWORD=…
```

Na DigitalOcean dropletu není trezor tajemství, proto soubor s přísnými právy. Do gitu nikdy.

### 4.5 nginx a HTTPS

1. Zkopíruj `deploy/nginx-s74-vouchers.conf` z repozitáře `s74-vouchers` do `/etc/nginx/sites-available/`, symlink do `sites-enabled/`, `sudo nginx -t && sudo systemctl reload nginx`.
2. Certifikát: `sudo apt install certbot python3-certbot-nginx && sudo certbot --nginx -d vouchery.s74.cz`. Obnova je automatická – ověř `sudo certbot renew --dry-run`.
3. Kontrola zvenku: `https://vouchery.s74.cz/login` se otevře s platným certifikátem; `https://vouchery.s74.cz/actuator/health` vrátí 403/404.
4. Volitelně (obrana do hloubky): pro `location /api/stripe/webhook` povolit jen IP adresy Stripe (`https://docs.stripe.com/ips` – seznam se mění, je třeba ho aktualizovat). Hlavní ochranou je ověření podpisu v aplikaci.

### 4.6 Aplikace

1. `deploy/s74-vouchers.service` → `/etc/systemd/system/`, pak `sudo systemctl daemon-reload && sudo systemctl enable --now s74-vouchers`.
2. Nasazení nové verze: `deploy/deploy.sh` z repozitáře (build, kopie, restart, kontrola zdraví).
3. Logy: `journalctl -u s74-vouchers -f`.
4. Po prvním startu se vytvoří první účet vedoucího z `S74_BOOTSTRAP_ADMIN_*`. Pak tyto dva řádky **z env smaž** a restartuj službu.

### 4.7 Zálohy databáze

1. `deploy/backup.sh` spouštěj denně přes cron/systemd timer (např. 3:30).
2. Zálohy šifruj a kopíruj **mimo droplet** (DigitalOcean Spaces, jiný server, NAS). Záloha na stejném serveru při jeho ztrátě nepomůže.
3. Uchovávej 30 denních záloh + měsíční po dobu, kterou určí účetní.
4. **Jednou za čtvrtletí zkus obnovu** do testovací databáze – záloha, kterou nikdo neobnovil, není záloha.

---

## 5. Účty obsluhy

Zadání: dva vlastníci a jeden brigádník, každý vlastní účet.

| Osoba | Role | Může |
|---|---|---|
| Vlastník 1 | MANAGER | vše: ověřit, uplatnit, opravy, zrušení, prodloužení, opětovné zaslání, účty, export |
| Vlastník 2 | MANAGER | totéž |
| Brigádník | STAFF | jen ověřit a uplatnit |

1. Přihlas se bootstrap účtem na `https://vouchery.s74.cz/login` → **Admin → Účty obsluhy**.
2. Založ zbylé účty. Hesla: aspoň 12 znaků, nejlépe vygenerovaná (`openssl rand -base64 18`), každý jiné, předej osobně.
3. Každý si uloží na telefon záložku `https://vouchery.s74.cz/staff` (nebo „Přidat na plochu“).
4. **Odchod brigádníka:** ještě týž den **Zneplatnit účet** – okamžitě ho to odhlásí. Nový brigádník = nový účet, nesdílet hesla.
5. Na sdíleném tabletu vždy na konci směny **Odhlásit** – jinak se akce zapisují pod posledním přihlášeným.

---

## 6. Provozní postupy pro obsluhu

Tuto kapitolu lze vytisknout a dát za bar.

### 6.1 Uplatnění voucheru

1. Zákazník ukáže QR kód (mobil / výtisk) nebo kód `XXXX-XXXX-XXXX`.
2. Naskenuj QR **fotoaparátem telefonu** a otevři odkaz – nebo v `vouchery.s74.cz/staff` napiš kód (mezery a malá písmena nevadí).
3. Zkontroluj: štítek **Platný** a hodnotu.
4. Je-li útrata **nižší** než hodnota voucheru, řekni zákazníkovi: *„Voucher je jednorázový, rozdíl X Kč propadne. Chcete si ještě něco dát?“*
5. Zaškrtni „Zákazníka jsem upozornil(a)…“, případně napiš číslo účtenky, a klikni **Uplatnit**.
6. Až uvidíš zelené **UPLATNĚNO**, odečti v pokladně hodnotu voucheru (způsobem domluveným v rozhodnutí 1.6).
7. Je-li útrata **vyšší**, zákazník doplatí rozdíl běžně.

Samotné otevření voucheru ho nespotřebuje – spotřebuje ho až tlačítko **Uplatnit**.

### 6.2 Když něco nesedí

| Co vidíš | Co dělat |
|---|---|
| „Kód nenalezen“ | Zkontroluj opsaný kód, zkus znovu nebo naskenuj QR. Po 10 neúspěšných pokusech se ověřování na 15 minut zablokuje. |
| **Uplatněn** (kdy, kým) | Neuplatňovat. Zákazníkovi ukaž, kdy byl použit; spor předej vedoucímu. |
| **Vypršel** | Neuplatňovat. Výjimku (prodloužení) může udělit jen vedoucí v adminu. |
| **Zrušen** | Neuplatňovat, předat vedoucímu. |
| Uplatnil(a) jsem omylem / jinou částku v pokladně | Hned napiš vedoucímu – vedoucí uplatnění vrátí v adminu a zapíše důvod. |
| Zákazníkovi nedorazil e-mail | Vedoucí ho najde v adminu podle e-mailu a pošle znovu (i na opravenou adresu). |

### 6.3 Výpadek systému nebo internetu

- **Voucher bez ověření v systému neuplatňovat** (zadání).
- Zkus jiné připojení (mobilní data místo Wi-Fi).
- Nejde-li to: omluv se, zákazník zaplatí běžně, voucher mu zůstane platný na příště. Vedoucí může výjimečně nabídnout jiné řešení podle domluveného postupu (rozhodnutí 1.7).
- Nikdy si „neodkládat“ uplatnění na později podle opsaného kódu – voucher mezitím může uplatnit někdo jiný.

---

## 7. Právo a účetnictví

Tyto body nejsou programování, ale bez nich by se neměl spustit ostrý prodej. Nejsou právní ani daňovou radou – ověř je s účetní a právníkem.

| ☐ | Úkol | S kým |
|---|---|---|
| ☐ | **DPH u voucheru:** voucher použitelný na kávu i víno (různé sazby) je nejspíš *víceúčelový poukaz* – DPH se odvádí až při uplatnění. Potvrdit postup a jak zaúčtovat prodej, uplatnění, propadlý rozdíl a vypršelé vouchery. | účetní |
| ☐ | Jak voucher zadat do pokladny (rozhodnutí 1.6) a zda je při prodeji potřeba nějaký doklad navíc kromě potvrzení od Stripe | účetní |
| ☐ | Jaký měsíční výstup účetní potřebuje (aplikace umí CSV export prodaných / uplatněných / zrušených / vypršelých) | účetní |
| ☐ | **Obchodní podmínky voucherů** – LLM připraví návrh (`obchodni-podminky-vouchery.html`), právník zkontroluje: platnost, propadnutí rozdílu, odstoupení od smlouvy do 14 dnů u nákupu online, reklamace, voucher na doručitele | právník |
| ☐ | **Ochrana osobních údajů** – doplnit `soukromi.html` (e-mail zákazníka, Stripe, e-mailová služba, DigitalOcean, doba uložení) | právník / ty |
| ☐ | Ověřit, že Stripe, e-mailová služba a DigitalOcean mají zpracovatelské smlouvy (DPA) – obvykle součást jejich podmínek, stačí si je uložit | ty |

Po každé změně podmínek se zvýší jejich verze v konfiguraci (`terms-version`) – aplikace u každé objednávky ukládá, se kterou verzí zákazník souhlasil.

---

## 8. Spuštění ostrého provozu

Až projde celý testovací scénář (analýza 17.2) na produkčním serveru se sandbox klíči:

1. ☐ Rozhodnutí z kap. 1 potvrzena, konfigurace (hodnoty, platnost) odpovídá.
2. ☐ Kap. 7 vyřízena, podmínky a soukromí jsou na webu.
3. ☐ Ostrý Stripe účet aktivovaný (2.4), výplaty na účet firmy nastavené.
4. ☐ V **ostrém režimu** vytvoř restricted key `s74-vouchers-backend-live` se stejnými právy jako v 2.3 a **IP omezením na droplet**.
5. ☐ V ostrém režimu vytvoř webhook endpoint podle 2.5 (stejná URL, stejné události, stejná API verze) → nový `whsec_…`.
6. ☐ Na serveru v `/etc/s74-vouchers/env` přepiš `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET` a nastav `STRIPE_LIVEMODE=true` → `sudo systemctl restart s74-vouchers`. Aplikace odmítne nastartovat, pokud klíč nesedí s režimem.
7. ☐ Smaž z databáze testovací objednávky a vouchery (nebo spusť ostrý provoz na čisté DB – dohodni s implementujícím LLM skript).
8. ☐ **Ostrý test:** kup nejlevnější voucher vlastní kartou → přijde e-mail → naskenuj QR → uplatni → ve Stripe proveď refund → v adminu ověř, že objednávka je „Vráceno“ a historie sedí. (Poplatek Stripe za tuto platbu se nevrací.)
9. ☐ Kup druhý voucher a nech ho aktivní – ověř, že se do adminu nic nepropsalo jako „k řešení“.
10. ☐ Na webu zapni odkaz „Vouchery“ v menu (merge PR z fáze F7 do `main`).
11. ☐ Proškol obsluhu (kap. 6), každý si vyzkouší přihlášení na svém telefonu.
12. ☐ Projdi oficiální kontrolní seznam Stripe: `https://docs.stripe.com/get-started/checklist/go-live`.

---

## 9. Pravidelná údržba a incidenty

### 9.1 Týdně

- ☐ Admin → **K řešení**: selhané e-maily, refundy použitých voucherů, spory, nesoulad plateb.
- ☐ Stripe e-maily o problémech s webhookem nebo spory.

### 9.2 Měsíčně

- ☐ CSV export pro účetní + Stripe report výplat.
- ☐ Aktualizace serveru (`apt`), WordPressu a pluginů.
- ☐ Kontrola, že zálohy vznikají a jsou mimo droplet.
- ☐ Kontrola účtů obsluhy – nikdo navíc, odešlí zneplatnění. Totéž v týmu Stripe a u e-mailové služby.
- ☐ Platnost certifikátu (obnovuje se sám, ale `sudo certbot certificates`).

### 9.3 Únik klíče nebo hesla

1. **Stripe klíč:** Dashboard → API keys → u klíče **Roll key** (okamžitě, i když si nejsi jistý, že byl zneužit) → nový klíč do env → restart aplikace. Zkontroluj v Workbench → Logs, jestli klíč nepoužil někdo cizí; při podezření kontaktuj podporu Stripe.
2. **Webhook secret:** u endpointu **Roll secret** → env → restart.
3. **SMTP heslo:** vygeneruj nové SMTP údaje ve službě, staré smaž.
4. **Heslo obsluhy:** v adminu nastav nové heslo / zneplatni účet.
5. **Heslo databáze:** `ALTER USER s74_vouchers PASSWORD '…'` → env → restart.

Klíče vyměň i preventivně, když odejde kdokoliv, kdo k nim měl přístup.

### 9.4 Aplikace neběží

1. `systemctl status s74-vouchers` a `journalctl -u s74-vouchers -n 200`.
2. Stripe mezitím webhooky drží a opakuje je až 3 dny – po opravě se vouchery za zaplacené objednávky vystaví samy. Kontrola: Stripe → Webhooks → endpoint → neúspěšné doručení lze poslat znovu ručně.
3. Obsluha postupuje podle 6.3.

## 10. Více podniků (Stripe Connect) – volitelné

Jen pokud se systém nabízí dalším podnikům (rozhodnutí: `vouchery-vice-klientu-connect.md`). S74 funguje i bez tohoto kroku.

1. **Stripe Dashboard platformy → Connect → Get started.** Typ platformy: software pro podniky, které prodávají vlastním
   zákazníkům; podniky jsou prodávající a mají plný Dashboard. Vyplnit profil platformy a počkat na schválení (ostrý režim).
2. **Klíč:** restricted key navíc s právy *Accounts* (write) a *Account Links* (write).
3. **Webhook pro připojené účty:** Developers → Webhooks → Add destination → *Events on: Connected accounts* →
   `https://{BACKEND}/api/stripe/connect-webhook`, události `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `checkout.session.async_payment_failed`, `checkout.session.expired`, `charge.refunded`, `charge.dispute.created`,
   `charge.dispute.closed`, `account.updated`, `account.application.deauthorized`. Secret → `STRIPE_CONNECT_WEBHOOK_SECRET`.
4. **v2 události účtů (doporučené):** Add destination → *Thin events* → `v2.core.account[configuration.merchant].capability_status_updated`
   a `v2.core.account[requirements].updated` → `https://{BACKEND}/api/stripe/account-events`. Secret → `STRIPE_ACCOUNT_EVENTS_WEBHOOK_SECRET`.
5. **Server `.env`:** `S74_CONNECT_ENABLED=true`, oba secrety, jednorázově `S74_BOOTSTRAP_PLATFORM_ADMIN_USERNAME/PASSWORD`
   (po prvním startu smazat). `docker compose up -d`.
6. **Nový podnik:** přihlásit se jako správce platformy → `/platform` → Nový podnik → založit vedoucího a předat mu údaje
   bezpečnou cestou → vedoucí v sekci **Platby (Stripe)** dokončí onboarding → po aktivaci Stripe se prodej spustí sám.
   Prodejní stránka podniku: `https://{BACKEND}/v/{slug}`.
7. **S74 na vlastní připojený účet (volitelné):** v detailu podniku S74 „Založit účet a otevřít onboarding“, po aktivaci
   „Přepnout na přímé platby“. Starší platby zůstávají na platformním účtu (refundy dělat tam).
