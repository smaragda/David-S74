# Podklady pro nasazení (F8) – zatím nepoužité

Soubory v tomto adresáři vznikly podle kap. 16 analýzy a **nebyly nikdy spuštěny na serveru**. Slouží jako
výchozí bod pro fázi F8 (nasazení na droplet DigitalOcean). Lokální vývoj je popsán v [../README.md](../README.md).
Postup pro člověka (server, DNS, Stripe, e-mail) je v `docs/vouchery-admin-navod.md`, kap. 4.

| Soubor | Účel |
|---|---|
| `nginx-s74-vouchers.conf` | server blok pro subdomenu backendu: TLS, HSTS, proxy na `127.0.0.1:8080`, `limit_req`, bez `access_log` pro `/staff/v/`, zákaz `/actuator` |
| `nginx-s74-vouchers-proxy.snippet.conf` | společné nastavení proxy (instaluje se jako `/etc/nginx/snippets/s74-vouchers-proxy.conf`) |
| `s74-vouchers.service` | systemd jednotka (uživatel `s74vouchers`, `EnvironmentFile=/etc/s74-vouchers/env`, hardening) |
| `deploy.sh` | build + test, kopie jaru, atomické přepnutí symlinku, restart, kontrola `/actuator/health` (spouští se ze stroje vývojáře přes SSH) |
| `backup.sh` | denní `pg_dump -Fc`, šifrování `age`, rotace 30 dní + měsíční kopie, volitelná kopie mimo droplet |
| `restore-test.sh` | obnova zálohy do dočasné testovací databáze a kontrola počtu řádků |

Před prvním nasazením ověřte (a případně upravte) výchozí hodnoty v hlavičce každého skriptu: doménu, cesty, SSH cíl.
Skripty ani konfigurace neobsahují žádná tajemství; tajné hodnoty patří do `/etc/s74-vouchers/env` (práva `640`, vlastník `root:s74vouchers`).

## Doporučené pořadí (shrnutí)

1. Server připravit dle admin návodu 4.1–4.4 (aktualizace, firewall, Java, PostgreSQL, uživatel, env soubor).
2. Nasadit `nginx-s74-vouchers.conf`, vystavit certifikát (`certbot --nginx -d <doména>`).
3. Nainstalovat `s74-vouchers.service`, spustit `deploy.sh`.
4. Nastavit `backup.sh` do cronu / systemd timeru a jednou vyzkoušet `restore-test.sh`.
5. Ověřit zvenku: `https://<doména>/login` má platný certifikát, `/actuator/health` vrací 403/404, porty 8080 a 5432 jsou zavřené.
