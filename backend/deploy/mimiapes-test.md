# Testovací nasazení na server mimiapes.cz (LiteSpeed + WordPress)

> **Stav:** návod nebyl na serveru nikdy spuštěn. Je to testovací nasazení se Stripe **sandboxem** (`STRIPE_LIVEMODE=false`), ne ostrý provoz.
> Obecné podklady pro nginx jsou v `README.md`; tento server ale používá **LiteSpeed**, takže nginx a `deploy.sh` se nepoužívají.

## Cílový stav

```
Prohlížeč ──https──► LiteSpeed (porty 80/443, drží ho WordPress)
                       ├─ mimiapes.cz, www.mimiapes.cz        → WordPress (beze změny)
                       └─ vouchery.mimiapes.cz  (nový vhost)  → proxy → 127.0.0.1:8080 → Docker: app + Postgres (+ Mailpit)
```

- Backend jde na **subdoménu** `vouchery.mimiapes.cz`. WordPress se nijak nepřesměrovává ani se nemění jeho vhost. V DNS přibude jeden A záznam.
- Aplikace **není** předřazená WordPressu: LiteSpeed zůstává jedinou vstupní bránou a rozlišuje podle jména hostitele.
- Port 8080 je v Dockeru publikovaný jen na `127.0.0.1`, zvenku se k aplikaci nedostanete jinak než přes LiteSpeed.
- E-maily zachytává **Mailpit** (nikdo nic nedostane). Skutečné SMTP se řeší až před ostrým provozem.
- Adresa v QR kódech bude `https://vouchery.mimiapes.cz/…`. Pro test to nevadí; pro ostrý provoz zvolte stabilní doménu, kterou budete mít navždy (změna = konfigurace + DNS).

Server (z DNS): `157.230.117.171`, NS Forpsi.

## 0. Zjistit stav serveru (webová konzole, jako root)

```bash
lsb_release -d; uname -m; free -h; df -h /
ls /usr/local/lsws/conf/ 2>&1 | head; systemctl list-units --type=service --state=running | grep -iE 'lsws|lshttpd|litespeed|mysql|maria|docker|nginx|apache|cyberpanel'
ss -tlnp | grep -E ':(80|443|7080|8080|8090|3306|5432) '
which docker certbot; docker --version 2>&1 | head -1
```

Výstup mi pošlete (bez hesel). Potřebuju hlavně: OpenLiteSpeed vs. Enterprise/CyberPanel, volnou RAM a názvy listenerů (viz krok 7).

## 1. DNS (Forpsi)

V administraci Forpsi → Domény → `mimiapes.cz` → DNS záznamy přidejte **jediný** záznam, ostatní nechte:

| Typ | Název | Hodnota |
|---|---|---|
| A | `vouchery` | `157.230.117.171` |

Kontrola z Macu: `dig +short vouchery.mimiapes.cz` vrátí IP serveru.

## 2. Stripe webhook (sandbox „TEST - David S74“)

Dashboard (v sandboxu) → Developers → Webhooks → Add endpoint: URL `https://vouchery.mimiapes.cz/api/stripe/webhook`, události
`checkout.session.completed`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`, `checkout.session.expired`,
`charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`. Zobrazte **Signing secret** (`whsec_…`) a uložte do správce hesel.
API verzi nechte výchozí; kód rozdílné verze toleruje (ověřeno: listener s `2026-10-28.endive` fungoval).
Endpoint můžete vytvořit hned, i když server ještě neběží. **Secret z `stripe listen` se sem nehodí**, ten je jiný.

## 3. Docker a uživatel (webová konzole, jako root)

```bash
apt update && apt install -y docker.io docker-compose-v2 certbot
docker compose version || echo "compose plugin chybí – použijte https://docs.docker.com/engine/install/ubuntu/"
# swap jako pojistka, pokud server nemá žádný (zkontrolujte `swapon --show` a `free -h`)
adduser --disabled-password --gecos "" deploy && usermod -aG docker deploy
install -d -o deploy -g deploy -m 750 /opt/s74-vouchers /opt/s74-vouchers/build/target
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
```

Docker publikuje porty přes iptables vedle firewallu; v tomto nasazení publikujeme jen `127.0.0.1`, takže se zvenku nic neotevře.

## 4. SSH klíč pro nahrávání (Mac → server)

Na Macu vytvořte samostatný klíč a vypište veřejnou část:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/mimiapes_deploy -N "" -C "s74-vouchers deploy"
cat ~/.ssh/mimiapes_deploy.pub
```

Řádek vložte v konzoli serveru (nahraďte `PUBLIC_KEY`):

```bash
echo 'PUBLIC_KEY' >> /home/deploy/.ssh/authorized_keys && chown deploy: /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
```

Test z Macu: `ssh -i ~/.ssh/mimiapes_deploy deploy@157.230.117.171 'docker ps'`. Pokud SSH nejde (zavřený port 22), napište a použijeme jiný přenos.

## 5. Konfigurace (`.env` se sestaví na Macu, tajné hodnoty se nekopírují přes chat)

Na Macu v `backend/`:

```bash
cd backend && set -a && . ./.env && set +a      # načte STRIPE_API_KEY (sandbox) z lokálního .env
read -rsp 'whsec_ z webhooku na Stripe: ' WHSEC; echo
ADMINPW=$(openssl rand -base64 18 | tr -d '/+=')
umask 077
cat > /tmp/s74-server.env <<EOF
COMPOSE_FILE=docker-compose.yml:docker-compose.mailpit.yml
DB_NAME=s74_vouchers
DB_USER=s74_vouchers
DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')
STRIPE_API_KEY=$STRIPE_API_KEY
STRIPE_WEBHOOK_SECRET=$WHSEC
STRIPE_LIVEMODE=false
S74_PUBLIC_BASE_URL=https://vouchery.mimiapes.cz
S74_FRONTEND_BASE_URL=http://localhost:8000
S74_CORS_ALLOWED_ORIGINS=http://localhost:8000
SMTP_HOST=mailpit
SMTP_PORT=1025
SMTP_USERNAME=test
SMTP_PASSWORD=test
S74_MAIL_FROM=S74 wine&café <vouchery@s74.cz>
S74_BOOTSTRAP_ADMIN_USERNAME=vedouci
S74_BOOTSTRAP_ADMIN_PASSWORD=$ADMINPW
EOF
echo "Heslo prvního vedoucího (uložte do správce hesel): $ADMINPW"
```

`S74_FRONTEND_BASE_URL` a `S74_CORS_ALLOWED_ORIGINS` míří zatím na `http://localhost:8000` (viz krok 9, fáze A).

## 6. Nahrání a spuštění

Na Macu v `backend/` (jar se sestaví lokálně, testy už prošly; image se staví až na serveru, takže odpadá emulace amd64):

```bash
H=deploy@157.230.117.171; K="-i $HOME/.ssh/mimiapes_deploy"
mvn -B -DskipTests package
scp $K /tmp/s74-server.env $H:/opt/s74-vouchers/.env
scp $K target/s74-vouchers.jar $H:/opt/s74-vouchers/build/target/
scp $K Dockerfile $H:/opt/s74-vouchers/build/
scp $K deploy/docker-compose.prod.yml $H:/opt/s74-vouchers/docker-compose.yml
scp $K deploy/docker-compose.mailpit.yml $H:/opt/s74-vouchers/
rm /tmp/s74-server.env
ssh $K $H 'chmod 600 /opt/s74-vouchers/.env && cd /opt/s74-vouchers/build && docker build -t s74-vouchers:current . && cd .. && docker compose up -d && sleep 60 && docker compose ps'
```

Kontrola na serveru: `curl -s http://127.0.0.1:8080/actuator/health` → `{"status":"UP",…}`, `docker stats --no-stream`, `free -h`, `docker compose logs --tail 50 app`.

## 7. LiteSpeed: virtuální host `vouchery.mimiapes.cz`

> Šablona pro **OpenLiteSpeed** (konfigurace v `/usr/local/lsws/conf/`). Názvy listenerů a existující vhosty se liší podle instalace, proto nejdřív výstup kroku 0
> a tohle z něj. Při jiném produktu (Enterprise, CyberPanel) je postup obdobný, ale jiný.

Před čímkoli zálohujte konfiguraci: `cp -a /usr/local/lsws/conf /usr/local/lsws/conf.bak-$(date +%F)`.

1. Adresář vhostu: `mkdir -p /var/www/vouchery/html /usr/local/lsws/conf/vhosts/vouchery`.
2. `/usr/local/lsws/conf/vhosts/vouchery/vhost.conf`:

   ```
   docRoot                   /var/www/vouchery/html
   vhDomain                  vouchery.mimiapes.cz
   enableGzip                1

   extprocessor s74backend {
     type                    proxy
     address                 127.0.0.1:8080
     maxConns                20
     pcKeepAliveTimeout      60
     initTimeout             60
     retryTimeout            0
     respBuffer              0
   }

   context /.well-known/ {
     location                /var/www/vouchery/html/.well-known/
     allowBrowse             1
   }

   context / {
     type                    proxy
     handler                 s74backend
     addDefaultCharset       off
     extraHeaders            RequestHeader set X-Forwarded-Proto https
   }

   rewrite  {
     enable                  1
     rules                   <<<END_rules
   RewriteRule ^/actuator - [F,L]
   RewriteCond %{HTTPS} !on
   RewriteCond %{REQUEST_URI} !^/\.well-known/
   RewriteRule ^(.*)$ https://%{HTTP_HOST}$1 [R=301,L]
   END_rules
   }
   ```

   (Blok `vhssl` s certifikátem se doplní po vydání, krok 4.)
3. V `/usr/local/lsws/conf/httpd_config.conf` přidejte blok

   ```
   virtualhost vouchery {
     vhRoot                  /var/www/vouchery
     configFile              $SERVER_ROOT/conf/vhosts/vouchery/vhost.conf
     allowSymbolLink         1
     enableScript            0
     restrained              1
   }
   ```

   a do **obou** existujících listenerů (HTTP :80 a HTTPS :443) řádek `map  vouchery vouchery.mimiapes.cz`. Existující `map` řádky WordPressu neměňte.
4. Certifikát (LiteSpeed zůstává na 80, proto webroot, ne standalone):

   ```bash
   mkdir -p /var/www/vouchery/html/.well-known/acme-challenge && /usr/local/lsws/bin/lswsctrl restart
   certbot certonly --webroot -w /var/www/vouchery/html -d vouchery.mimiapes.cz
   ```

   Do `vhost.conf` pak přidejte:

   ```
   vhssl  {
     keyFile                 /etc/letsencrypt/live/vouchery.mimiapes.cz/privkey.pem
     certFile                /etc/letsencrypt/live/vouchery.mimiapes.cz/fullchain.pem
     certChain               1
   }
   ```

   a `/usr/local/lsws/bin/lswsctrl restart`. Obnovu certifikátu doplní `deploy-hook`: `certbot renew --deploy-hook "/usr/local/lsws/bin/lswsctrl restart"`, otestujte `certbot renew --dry-run`.
5. **Návrat při chybě:** `rm -r /usr/local/lsws/conf && cp -a /usr/local/lsws/conf.bak-<datum> /usr/local/lsws/conf && /usr/local/lsws/bin/lswsctrl restart`. WordPress se vhostem neovlivní, ale po každém restartu hned ověřte, že `https://mimiapes.cz` odpovídá.

## 8. Kontrola zvenku (z Macu)

```bash
curl -sI https://vouchery.mimiapes.cz/login | head -5                                          # 200, platný certifikát
curl -s -o /dev/null -w '%{http_code} -> %{redirect_url}\n' http://vouchery.mimiapes.cz/login    # 301 -> https
curl -s -o /dev/null -w '%{http_code}\n' https://vouchery.mimiapes.cz/actuator/health           # 403 (blokuje pravidlo ve vhostu)
curl -sI https://mimiapes.cz | head -3                                                          # WordPress dál běží
curl -s https://vouchery.mimiapes.cz/api/public/offers | head -c 200
```

**Přesměrování po přihlášení musí být na `https://`.** Zkontrolujte přihlášení obsluhy v prohlížeči (`https://vouchery.mimiapes.cz/login`). Pokud odkazy nebo `Location` hlavičky jdou na `http://`, nepředává LiteSpeed `X-Forwarded-Proto` (řádek `extraHeaders`); upravte nastavení vhostu nebo napište, vyřešíme to v aplikaci.

`/actuator/health` je v aplikaci dostupný (používá ho healthcheck Dockeru), zvenku ho blokuje pravidlo `RewriteRule ^/actuator - [F,L]` ve vhostu z kroku 7.

## 9. Frontend (GitHub Pages) a CORS

**Fáze A – testování lokálním webem** (výchozí; nic se nezveřejní):

- CORS i návratová adresa míří na `http://localhost:8000` (viz `.env` v kroku 5).
- Na Macu v kopii webu (bez `backend/`) nastavte `js/vouchers-config.js` na `window.S74_VOUCHER_API = 'https://vouchery.mimiapes.cz';` a servírujte `python3 -m http.server 8000 --bind 127.0.0.1`. Soubor ve větvi neměňte, dokud nejde o produkci.
- Test: `http://localhost:8000/vouchery.html` → nákup kartou `4242 4242 4242 4242` → po platbě Stripe vrátí na `localhost:8000`.
- E-maily: `ssh -i ~/.ssh/mimiapes_deploy -L 8025:127.0.0.1:8025 deploy@157.230.117.171` a `http://localhost:8025`.
- Obsluha: `https://vouchery.mimiapes.cz/login` (účet `vedouci`, heslo z kroku 5).

**Fáze B – web na `s74.cz`** (až bude hotové SMTP a rozhodnuto o doméně backendu):

1. V `.env` na serveru: `S74_FRONTEND_BASE_URL=https://s74.cz`, `S74_CORS_ALLOWED_ORIGINS=https://s74.cz,https://www.s74.cz` a `cd /opt/s74-vouchers && docker compose up -d` (znovu načte env).
2. Ve větvi: `js/vouchers-config.js` → `https://vouchery.mimiapes.cz` (nebo finální doména), PR #2 sloučit do `main`; GitHub Pages web nasadí. Do té doby vouchery na veřejném webu nejsou.
3. Veřejné stránky ukazují lištu „Testovací režim“, dokud je `STRIPE_LIVEMODE=false`.

## 10. Co musí před ostrým provozem (mimo tento test)

Skutečný SMTP (SPF/DKIM/DMARC), live Stripe účet firmy a nový webhook, zálohy databáze mimo server, právní kontrola podmínek, rozhodnutí o finální doméně backendu, aktualizace WordPressu a serveru (viz `docs/vouchery-admin-navod.md`).

## Údržba testovacího běhu

```bash
cd /opt/s74-vouchers
docker compose logs -f app          # logy
docker compose restart app          # restart
docker compose down                 # zastavení (data zůstanou ve volume pgdata)
docker compose down -v              # zastavení včetně smazání databáze
```

Nová verze: na Macu znovu `mvn -B -DskipTests package`, `scp` jaru, na serveru `docker build -t s74-vouchers:current . && cd .. && docker compose up -d`.
