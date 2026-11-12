# Nasazení na droplet 1 GB (Docker) – podklady pro F8

> **Stav:** soubory v tomto adresáři **nebyly nikdy spuštěny na serveru**. Lokálně ověřeno: sestavení image, start produkčního
> stacku `docker-compose.prod.yml` se stejnými limity paměti, healthcheck, read-only FS, nepublikovaný Postgres a spotřeba paměti
> (viz níže). Neověřeno: nginx konfigurace, certbot, `deploy.sh`, `backup.sh` (šifrování `age`), `restore-test.sh`, přenos image přes ssh.

Architektura: **nginx + certbot na hostu**, v Dockeru **PostgreSQL 16** a **aplikace** (Spring Boot, JRE 21 Alpine). Aplikace je dostupná jen na
`127.0.0.1:8080` hostitele, Postgres se nepublikuje vůbec (jen vnitřní síť compose). Postup pro člověka (Stripe, e-mail, DNS) je v
`docs/vouchery-admin-navod.md` (kap. 2, 3, 5–8); kroky ze serverové kap. 4 nahrazuje tento návod.

| Soubor | Účel |
|---|---|
| `docker-compose.prod.yml` | stack postgres + app s limity paměti, healthchecky, `read_only`, `cap_drop: ALL` |
| `env.prod.example` | vzor `/opt/s74-vouchers/.env` (tajné hodnoty, jen na serveru) |
| `../Dockerfile` | runtime image (jen balí hotový jar) |
| `deploy.sh` | testy + jar → image `linux/amd64` → přenos přes ssh → přepnutí tagu `current` → health check → návrat na `previous` |
| `backup.sh`, `restore-test.sh` | denní šifrovaná záloha (`pg_dump` v kontejneru + `age`), zkušební obnova |
| `nginx-s74-vouchers.conf`, `nginx-s74-vouchers-proxy.snippet.conf` | reverzní proxy: TLS, HSTS, `limit_req`, bez `access_log` pro `/staff/v/`, zákaz `/actuator` |
| `native/` | původní varianta bez Dockeru (systemd + nativní Java + Postgres z apt), nepoužívá se |

## Paměť (1 GB RAM)

Změřeno lokálně na produkčním stacku po 600 požadavcích (tj. bez nákupů): **aplikace ≈ 280 MiB** (limit kontejneru 520), **Postgres ≈ 30 MiB**
(limit 200). Dohromady ~310 MB; k tomu Ubuntu + dockerd + nginx (≈ 250–300 MB) → zhruba 600 MB z 1 GB, plus swap jako pojistka.
Škrcení: JVM `-Xmx256m`, SerialGC, jen C1 kompilátor, `-Xss512k`, metaspace 128 MB (`JAVA_TOOL_OPTIONS` v compose);
Spring: Hikari pool 5, Tomcat max 16 vláken (`application-prod.yml`); Postgres `shared_buffers=48MB`, `max_connections=20`.
Reálnou spotřebu po nasazení zkontrolujte: `docker stats --no-stream` a `free -h`. Při tlaku na paměť je prvním krokem resize dropletu na 2 GB (jen CPU/RAM, vratné).

## Postup na čerstvém dropletu (Ubuntu 24.04, 1 GB)

1. **DNS:** A záznam `vouchery.s74.cz` (u Forpsi) na IP dropletu.
2. **Základ** (jako root, nejdřív snapshot dropletu v DigitalOcean):

   ```bash
   apt update && apt full-upgrade -y && apt install -y unattended-upgrades ufw nginx certbot python3-certbot-nginx docker.io docker-compose-v2 age
   # swap 2 GB jako pojistka pro 1 GB RAM
   fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
   echo '/swapfile none swap sw 0 0' >> /etc/fstab && echo 'vm.swappiness=20' > /etc/sysctl.d/99-swap.conf && sysctl --system
   # firewall: jen 22, 80, 443 (8080 ani 5432 zvenku nesmí být dostupné)
   ufw allow OpenSSH && ufw allow 'Nginx Full' && ufw --force enable
   # uživatel pro nasazení (SSH klíč, bez hesla), členem skupiny docker
   adduser --disabled-password --gecos "" deploy && usermod -aG docker deploy
   mkdir -p /home/deploy/.ssh && cp ~/.ssh/authorized_keys /home/deploy/.ssh/ && chown -R deploy: /home/deploy/.ssh
   install -d -o deploy -g deploy -m 750 /opt/s74-vouchers
   ```

   SSH jen klíčem (`PasswordAuthentication no`, `PermitRootLogin prohibit-password` – nejdřív ověřte přihlášení klíčem).
3. **Konfigurace aplikace:** zkopírujte `env.prod.example` na server jako `/opt/s74-vouchers/.env`, `chmod 600`, vyplňte hodnoty
   (nejdřív sandbox klíče + `STRIPE_LIVEMODE=false`, bootstrap účet vedoucího). Heslo k DB: `openssl rand -base64 24`.
4. **nginx + certifikát:**

   ```bash
   cp nginx-s74-vouchers-proxy.snippet.conf /etc/nginx/snippets/s74-vouchers-proxy.conf
   cp nginx-s74-vouchers.conf /etc/nginx/sites-available/s74-vouchers.conf && ln -s ../sites-available/s74-vouchers.conf /etc/nginx/sites-enabled/
   # před certbotem dočasně zakomentujte server blok 443 nebo použijte `certbot certonly --nginx -d vouchery.s74.cz`
   certbot --nginx -d vouchery.s74.cz && nginx -t && systemctl reload nginx && certbot renew --dry-run
   ```
5. **První nasazení** (z vašeho stroje, z adresáře `backend/`): `DEPLOY_HOST=deploy@vouchery.s74.cz ./deploy/deploy.sh`
   (Docker Desktop na Apple Silicon sestaví `linux/amd64` image emulací; trvá déle, ale funguje).
6. **Po prvním startu:** přihlaste se na `https://vouchery.s74.cz/login`, vyplňte účty obsluhy, pak z `.env` **smažte** `S74_BOOTSTRAP_ADMIN_*`
   a `cd /opt/s74-vouchers && docker compose up -d`.
7. **Kontrola zvenku:** `/login` má platný certifikát; `/actuator/health` vrací 403; porty 8080 a 5432 jsou zavřené (`nmap` z jiného stroje).
8. **Zálohy:** cron / systemd timer pro `backup.sh` (např. 3:30, s `AGE_RECIPIENT`), kopie mimo droplet (`rclone`), čtvrtletně `restore-test.sh`.
   Soukromý klíč `age` uložte mimo server.
9. **Stripe:** webhook endpoint `https://vouchery.s74.cz/api/stripe/webhook` v Dashboardu (stejná API verze jako SDK), události dle analýzy 10.3; `whsec_…` do `.env`.

Logy: `docker compose logs -f app` (rotace v compose: 3 × 10 MB). Restart: `docker compose restart app`. Návrat na předchozí verzi je automatický při neúspěšném health checku
v `deploy.sh`; ručně `docker tag s74-vouchers:previous s74-vouchers:current && docker compose up -d`.
