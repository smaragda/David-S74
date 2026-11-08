// Adresa backendu voucherů – jediné místo, které se mění podle prostředí.
// Lokální vývoj: backend (Spring Boot) na http://127.0.0.1:8080 (ne localhost: na některých strojích na 8080 poslouchá jiná služba), web přes `python3 -m http.server 8000`.
// Produkce: doplnit adresu nasazeného backendu (např. https://vouchery.s74.cz) před nasazením webu.
window.S74_VOUCHER_API = 'http://127.0.0.1:8080';
// Jiný podnik na téže platformě: slug podniku (prázdné = výchozí podnik S74).
window.S74_VOUCHER_TENANT = '';
