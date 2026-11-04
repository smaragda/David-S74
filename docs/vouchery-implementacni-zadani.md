# Implementační zadání: dárkové vouchery pro vinárnu

## Cíl a pravidla

Zákazník koupí voucher online, zaplatí přes Stripe a dostane e-mailem unikátní kód a QR kód. Voucher lze darovat a uplatnit právě jednou při nákupu ve vinárně.

- Voucher má peněžní hodnotu a platnost.
- Je-li nákup levnější, nevyužitý rozdíl propadne; zákazník na to musí být před nákupem i při uplatnění upozorněn.
- Je-li nákup dražší, zákazník doplatí rozdíl.
- Pokladna není v první verzi propojená: obsluha provede odečet ručně.

## Architektura

- Veřejný nákup: stránka na stávajícím webu vinárny.
- Backend: samostatná aplikace na existujícím serveru DigitalOcean pod jeho současnou doménou. Webový server ji vystaví přes HTTPS a předává požadavky na interní port.
- CORS: backend povolí přesný origin webu vinárny. CORS nenahrazuje autentizaci ani autorizaci.
- Obsluha: jednoduchá stránka na doméně backendu, použitelná i na mobilu.
- Platby: Stripe Checkout. Při vývoji testovací režim; pro ostrý provoz přednostně vlastní Stripe účet vinárny.
- Úložiště a e-mail: databáze objednávek, voucherů a historie; služba pro doručování e-mailů.

## Nákup a vystavení

1. Zákazník vybere hodnotu, zadá e-mail a potvrdí seznámení s podmínkami.
2. Backend ověří vstupy a cenu podle vlastního ceníku, založí objednávku ve stavu čeká na platbu a vytvoří Stripe Checkout relaci.
3. Zákazník zaplatí na stránce Stripe.
4. Backend přijme webhook, ověří jeho podpis, přiřazení k objednávce a skutečný stav platby.
5. Po potvrzeném zaplacení označí objednávku jako zaplacenou a vytvoří jeden aktivní voucher s náhodným unikátním kódem.
6. Odešle e-mail s kódem, QR kódem, hodnotou, platností a podmínkami.
7. Návratová stránka zobrazuje stav objednávky; samotný návrat není důkazem platby. Pokud potvrzení ještě nedorazilo, zobrazí čekání.

Zpracování webhooku musí být idempotentní: opakované doručení nesmí vytvořit další voucher. Zaznamenat stav odeslání e-mailu a umožnit jeho bezpečné opakování. U odložených platebních metod čekat na skutečné potvrzení úspěšné platby.

## Uplatnění

1. Přihlášená obsluha zadá kód nebo načte QR.
2. Aplikace zobrazí hodnotu, platnost a stav. Pouhé zobrazení voucher nespotřebuje.
3. Obsluha sdělí zákazníkovi případné propadnutí rozdílu a potvrdí uplatnění.
4. Backend v jedné transakci znovu ověří stav a označí voucher za použitý. Při souběhu smí uspět pouze jeden požadavek.
5. Zaznamená čas a účet obsluhy; obsluha provede odečet v pokladně. Volitelně uložit údaj pro dohledání účtenky.

## Přístup a zabezpečení

- První verze může používat HTTP Basic Auth přes HTTPS se třemi samostatnými účty: dva vlastníci a jeden brigádník. Při odchodu zaměstnance jeho účet zneplatnit.
- Omezit pokusy o přihlášení i hádání voucherových kódů; zaznamenávat bezpečnostní události bez hesel a tajných údajů.
- Uplatnění a administrátorské zásahy ověřovat na backendu. Opravy a zrušení uplatnění vyhradit oprávněné osobě a zaznamenat.
- Kód generovat kryptograficky bezpečně a zajistit jeho unikátnost; interní identifikátor může být UUID. Konkrétní délku kódu zvolit podle prostoru kódů a limitů pokusů.
- Stripe tajné klíče a tajemství webhooku držet pouze na backendu. Aktualizovat server i WordPress, oddělit přístupy a zálohovat databázi.
- Basic Auth má omezené odhlašování na sdílených zařízeních; pokud to bude provozně vadit, nahradit jej hotovým přihlašováním se serverovou relací.

## Chybové scénáře

| Situace | Očekávané chování |
|---|---|
| Platba selže nebo je opuštěna | Voucher se neaktivuje; zákazník může platbu zopakovat. |
| Zákazník po zaplacení zavře stránku | Webhook přesto umožní vystavení a odeslání voucheru. |
| E-mail nedorazí | Oprávněná osoba dohledá objednávku a po ověření zajistí opětovné zaslání. |
| Překlep nebo neexistující kód | Srozumitelná chyba a nový pokus; tolerovat mezery a velikost písmen. |
| Již použitý, vypršelý nebo zrušený voucher | Uplatnění zamítnout, zobrazit stav a předat případný spor vedoucímu. |
| Dva souběžné pokusy | Uspěje pouze první potvrzené uplatnění. |
| Výpadek systému nebo internetu | Voucher bez ověření v systému neuplatňovat; řídit se předem domluveným náhradním postupem. |
| Chyba mezi uplatněním a odečtem v pokladně | Dohledat záznam a řešit nápravu oprávněnou osobou; změnu zaznamenat. |
| Vrácení platby | Prověřit stav voucheru a zneplatnit nepoužitý; použitý řešit individuálně. |

## Data a otevřená rozhodnutí

Ukládat objednávku a stav platby; voucher, jeho hodnotu, kód, platnost a stav; historii uplatnění a administrátorských změn; stav odeslání e-mailu.

Před implementací rozhodnout konkrétní hodnoty voucherů a délku platnosti; poskytovatele e-mailu; postup pro výpadek, reklamace a opravy; kdo má oprávnění vedoucího; zda budou QR kódy v první verzi nutné.

## QR kód a rychlé ověření obsluhou

QR obsahuje odkaz na stránku obsluhy s identifikací voucheru; neobsahuje heslo ani přihlašovací údaje. Po načtení se případně zobrazí přihlášení Basic Auth a po jeho úspěchu rovnou hodnota a stav voucheru. Nejde o single sign-on: obsluha se přihlašuje samostatně. Otevření odkazu ani načtení QR voucher nespotřebuje; uplatnění vyžaduje samostatné potvrzení a kontrolu na serveru. Kód je třeba chránit jako poukaz na doručitele; omezit jeho zbytečné ukládání do logů a předávání dalším službám.

## Odesílání voucherů e-mailem

Prověřit použití existujícího poskytovatele schránky vinárny, pokud umožňuje aplikaci bezpečně odesílat poštu, nebo transakční e-mailovou službu. Ověření vlastní odesílací domény může vyžadovat úpravu DNS; nejdříve zkontrolovat současné nastavení. Neprovozovat vlastní poštovní server jen kvůli této funkci. Otestovat doručení, evidovat selhání a umožnit opakované odeslání. Pro produkční službu ověřit limity a vhodnost konkrétního poskytovatele.

## Alternativa: Jotform a Stripe

Jotform umí sestavit platební formulář se Stripe a předávat odeslané formuláře backendu pomocí webhooku. Může ušetřit tvorbu formuláře a jeho platební integrace; má také vlastní automatické e-maily. Odeslání formuláře ale samo o sobě není důkazem úspěšné platby. Pro hybridní řešení je nutné ověřit bezpečné přijetí zpráv, spolehlivé spárování formuláře s potvrzenou Stripe platbou, její skutečný stav, opakované zprávy a způsob předání vytvořeného kódu a QR do e-mailu až po zaplacení. Ověřit rovněž limity a cenu zvoleného tarifu.

Doporučená výchozí varianta: vlastní jednoduchý formulář a přímá integrace Stripe Checkout. Stripe poskytuje hostovanou platební stránku, takže Jotform není nutný k vytvoření platebního rozhraní. Backend je v každém případě potřebný pro vystavení voucherů, jejich stav a jednorázové uplatnění. Jotform zvážit, pokud jeho správa formulářů nebo e-mailů pro vinárnu prokazatelně převáží další integraci; před přijetím ověřit celý průchod včetně selhané a opožděné platby.

Zdroje: [Jotform: webhook formuláře](https://www.jotform.com/help/245-how-to-send-submission-data-via-a-webhook/), [Jotform: integrace Stripe](https://www.jotform.com/help/190-how-to-integrate-stripe-with-jotform/), [Stripe: Checkout a potvrzení platby](https://docs.stripe.com/payments/existing-customers?platform=web&ui=stripe-hosted), [Jotform: automatické e-maily](https://www.jotform.com/help/26-setting-up-an-autoresponder-email/).
