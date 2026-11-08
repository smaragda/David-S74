// NÁHLED BEZ BACKENDU: zastoupí API ukázkovými daty, aby šlo stránky otevřít přímo ze souboru (file://).
window.S74_VOUCHER_API = 'http://preview.invalid';
(function () {
    var polls = {};
    function json(status, body) {
        return Promise.resolve(new Response(JSON.stringify(body), { status: status, headers: { 'Content-Type': 'application/json' } }));
    }
    window.fetch = function (url, options) {
        var u = String(url);
        if (u.indexOf('/api/public/offers') !== -1) {
            return json(200, {
                currency: 'CZK', validityMonths: 12, termsVersion: '2026-11', termsUrl: 'obchodni-podminky-vouchery.html',
                offers: [500, 1000, 2000].map(function (n) {
                    var s = String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
                    return { id: 'CZK_' + n, amountCzk: n, label: 'Voucher v hodnotě ' + s + ' Kč' };
                })
            });
        }
        if (u.indexOf('/api/public/checkout-sessions') !== -1) {
            // náhled: místo platební stránky Stripe rovnou na stav objednávky (úspěšná platba)
            return json(201, { checkoutUrl: 'order-success.html?session_id=cs_demo_paid' });
        }
        if (u.indexOf('/api/public/orders/status') !== -1) {
            var id = new URL(u, 'http://x').searchParams.get('session_id');
            polls[id] = (polls[id] || 0) + 1;
            if (id === 'cs_demo_failed') return json(200, { status: 'FAILED', orderRef: '7F3A9C21', amountCzk: 1000 });
            if (id === 'cs_demo_emailfail') return json(200, { status: 'PAID', orderRef: '7F3A9C21', amountCzk: 1000, emailMasked: 'j***@e***.com', emailStatus: 'FAILED' });
            if (id === 'cs_demo_pending') return json(200, { status: 'PENDING', orderRef: '7F3A9C21', amountCzk: 1000 });
            if (polls[id] < 3) return json(200, { status: 'PENDING', orderRef: '7F3A9C21', amountCzk: 1000 });
            return json(200, { status: 'PAID', orderRef: '7F3A9C21', amountCzk: 1000, emailMasked: 'j***@e***.com', emailStatus: 'PENDING', voucher: { code: 'K7M2-Q9X4-B8TW', validUntil: '2027-11-08', amountCzk: 1000, qrDataUri: 'data:image/svg+xml;utf8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="#fff"/><g fill="#000"><rect x="8" y="8" width="26" height="26"/><rect x="66" y="8" width="26" height="26"/><rect x="8" y="66" width="26" height="26"/><rect x="44" y="44" width="12" height="12"/><rect x="70" y="70" width="20" height="8"/></g></svg>') } });
        }
        return Promise.reject(new TypeError('offline preview'));
    };
})();
