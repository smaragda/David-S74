# Správa webu klientem (neprogramátorem)

## Kontext projektu
- Pure static HTML/CSS/JS — žádný framework, žádný build step
- `data/wines.json` již existuje jako datový soubor (klíčový vzor pro rozšíření)
- GitHub → Vercel autodeploy (commit = deploy)
- Git jako síť bezpečnosti při chybě (snadný rollback)

---

## Přehled možností

### A. JAMstack CMS (Decap CMS)
Přidá se do repozitáře, majitel jde na `vaseadresa.cz/admin`, přihlásí se přes GitHub, vidí formuláře.

**Výhody:** Zero kódu pro majitele, zero průběžných nákladů, Publish → commit → deploy automaticky.
**Nevýhody:** Nutný setup (přidání Decap do repozitáře, konfigurace polí, GitHub OAuth app).
**Alternativy:** TinaCMS (live preview), CloudCannon (hezčí UI, ale placený).

Protože `wines.json` už existuje, lze na něj Decap jednoduše napojit. Stejný vzor rozšiřitelný na events, menu, otevírací dobu.

---

### B. GitHub web editor + LLM chat ✅ Preferovaná varianta
Majitel edituje soubory přímo na github.com, vedle otevřený LLM chat (Claude, ChatGPT).

**Workflow:**
1. Otevře soubor na github.com → tužka (edit)
2. Vedle v LLM chatu popíše co chce změnit, přiloží kontext
3. LLM mu řekne přesně co změnit nebo dá hotový úsek kódu
4. Zkopíruje → commit → Vercel nasadí

**Výhody:**
- Zero setup navíc — funguje hned teď
- Git rollback při pokažení
- Majitel se postupně přirozeně učí
- Funguje i pro HTML, nejen JSON

**Rizika:**
- LLM může halucinovat → majitel nepozná chybu
- Při větším souboru je těžší LLM dát správný kontext
- Může nechtěně rozbít CSS třídy nebo JS hooky

**Zmírnění rizik:**
- Připravit "prompt šablony" pro nejčastější úkony (přidat akci, změnit text, přidat víno)
- Editovatelný obsah přesunout do JSON souborů — tam je chyba méně pravděpodobná a snáze viditelná

---

### C. Bez možnosti
Neplatí — možností je dost.

---

### D. Micro CMS s LLM prompty
Admin panel kde majitel píše "změň otevírací dobu na 8–22" a LLM to provede.

**Výhody:** Nejpřirozenější UX pro netechnického uživatele.
**Nevýhody:** Potřebuje backend (serverless), API klíč (průběžné náklady), vlastní autentizaci. Složité na build i údržbu.
**Verdict:** Overkill, když Decap CMS nebo B řeší 90 % případů bez nákladů.

---

### E. Jules (Google Labs)
AI coding agent napojený na GitHub. Majitel vytvoří Issue: "Změň otevírací dobu..." → Jules udělá PR.

**Výhody:** Žádný kód z pohledu majitele.
**Nevýhody:** Stále vyžaduje práci s GitHubem (issues, PR review), experimentální nástroj.
**Verdict:** Zajímavý doplněk, ne primární řešení.

---

## Doporučení

**Krátkodobě (hned):** Varianta B — GitHub editor + LLM chat. Funguje bez dodatečného setupu, git chrání před nezvratnou chybou.

**Střednědobě:** Přesunout editovatelný obsah do JSON souborů:
- `data/wines.json` — již hotovo
- `data/events.json` — akce
- `data/menu.json` — menu
- `data/info.json` — otevírací hodiny, kontakt

Majitel pak edituje pouze strukturovaná data, ne HTML — menší riziko rozbití layoutu.

**Volitelně:** Přidat Decap CMS jako formulářové rozhraní nad tyto JSON soubory, pokud by se ukázalo, že JSON editace je pro klienta stále příliš technická.
