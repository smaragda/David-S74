// Adresa backendu voucherů – jediné místo, které se mění podle prostředí.
// Lokální vývoj: backend (Spring Boot) na http://localhost:8080, web přes `python3 -m http.server 8000`.
// Produkce: doplnit adresu nasazeného backendu (např. https://vouchery.s74.cz) před nasazením webu.
window.S74_VOUCHER_API = 'http://localhost:8080';
