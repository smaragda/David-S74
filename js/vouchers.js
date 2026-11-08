// Nákup dárkových voucherů: nabídka, objednávka a stav objednávky.
// Adresa backendu je v js/vouchers-config.js. Frontend neobsahuje žádný Stripe klíč; voucher nikdy nevystavuje.
(function () {
    'use strict';

    const API = String(window.S74_VOUCHER_API || '').replace(/\/+$/, '');
    const NBSP = ' ';

    const ERROR_MESSAGES = {
        INVALID_EMAIL: 'Zadejte platnou e-mailovou adresu.',
        INVALID_OFFER: 'Vybraný voucher už není dostupný. Vyberte prosím jiný.',
        TERMS_NOT_ACCEPTED: 'Pro pokračování je nutný souhlas s obchodními podmínkami.',
        TERMS_OUTDATED: 'Obchodní podmínky se mezitím změnily. Zkontrolujte je a potvrďte souhlas znovu.',
        RATE_LIMITED: 'Příliš mnoho pokusů. Zkuste to prosím za chvíli.',
        PAYMENT_PROVIDER_UNAVAILABLE: 'Platební brána je teď nedostupná. Zkuste to prosím za chvíli.',
        INVALID_REQUEST: 'Požadavek se nepodařilo zpracovat. Zkuste to prosím znovu.'
    };
    const NETWORK_ERROR = 'Nepodařilo se spojit se serverem. Zkontrolujte připojení a zkuste to znovu.';
    const GENERIC_ERROR = 'Něco se nepovedlo. Zkuste to prosím znovu.';

    function formatCzk(amount) {
        return new Intl.NumberFormat('cs-CZ').format(amount).replace(/\s/g, NBSP) + NBSP + 'Kč';
    }

    function byId(id) {
        return document.getElementById(id);
    }

    function show(element, visible) {
        if (element) element.hidden = !visible;
    }

    // Vrací { status, data }; data je JSON nebo null. Při síťové chybě vyhodí výjimku.
    async function request(path, options) {
        const response = await fetch(API + path, options);
        let data = null;
        try {
            data = await response.json();
        } catch (error) {
            data = null;
        }
        return { status: response.status, ok: response.ok, data: data };
    }

    function loadOffers() {
        return request('/api/public/offers', { headers: { Accept: 'application/json' } }).then(function (result) {
            if (!result.ok || !result.data || !Array.isArray(result.data.offers)) {
                throw new Error('offers unavailable');
            }
            return result.data;
        });
    }

    function setValidity(months) {
        const element = byId('validity-months');
        if (element && months) element.textContent = String(months);
    }

    // --- vouchery.html -------------------------------------------------------------------------

    function initOffers() {
        const grid = byId('offer-grid');
        const errorBox = byId('offers-error');

        loadOffers().then(function (data) {
            grid.textContent = '';
            grid.setAttribute('aria-busy', 'false');
            setValidity(data.validityMonths);

            data.offers.forEach(function (offer) {
                const card = document.createElement('article');
                card.className = 'offer-card';

                const body = document.createElement('div');
                const price = document.createElement('p');
                price.className = 'offer-price';
                price.textContent = formatCzk(offer.amountCzk);
                const title = document.createElement('h3');
                title.textContent = offer.label;
                title.className = 'offer-title';
                const text = document.createElement('p');
                text.textContent = 'Ideální jako dárek. Lze využít na cokoli z naší nabídky – od ranní kávy až po večerní láhev vína.';
                body.append(price, title, text);

                const actions = document.createElement('div');
                actions.className = 'offer-actions';
                const link = document.createElement('a');
                link.className = 'btn btn-accent';
                link.href = 'checkout.html?offer=' + encodeURIComponent(offer.id);
                link.textContent = 'Koupit voucher';
                link.setAttribute('aria-label', 'Koupit ' + offer.label.toLowerCase());
                actions.append(link);

                card.append(body, actions);
                grid.append(card);
            });
        }).catch(function () {
            grid.textContent = '';
            grid.setAttribute('aria-busy', 'false');
            show(errorBox, true);
        });
    }

    // --- checkout.html -------------------------------------------------------------------------

    function initCheckout() {
        const params = new URLSearchParams(window.location.search);
        const offerId = params.get('offer');
        const content = byId('checkout-content');
        const loading = byId('loading-note');
        const offerError = byId('offer-error');
        const form = byId('checkout-form');
        const button = byId('submit-button');
        const formError = byId('form-error');
        const emailError = byId('email-error');
        const email = byId('email');
        const email2 = byId('email2');
        const terms = byId('terms');
        let termsVersion = null;

        show(byId('cancelled-note'), params.get('cancelled') === '1');

        function showFormError(message) {
            formError.textContent = message;
            show(formError, true);
        }

        function setBusy(busy) {
            button.disabled = busy;
            button.textContent = busy ? 'Přesměrovávám na platbu…' : 'Zaplatit kartou';
        }

        function applyOffers(data) {
            const offer = data.offers.find(function (item) { return item.id === offerId; });
            show(loading, false);
            if (!offer) {
                byId('offer-error-text').textContent = 'Vybraný voucher jsme nenašli.';
                show(offerError, true);
                show(content, false);
                return null;
            }
            termsVersion = data.termsVersion;
            byId('offer-price').textContent = formatCzk(offer.amountCzk);
            byId('offer-name').textContent = offer.label;
            if (data.termsUrl) byId('terms-link').href = data.termsUrl;
            setValidity(data.validityMonths);
            show(offerError, false);
            show(content, true);
            return offer;
        }

        loadOffers().then(applyOffers).catch(function () {
            show(loading, false);
            byId('offer-error-text').textContent = 'Prodej voucherů je teď dočasně nedostupný, zkuste to prosím později.';
            show(offerError, true);
        });

        // Po návratu tlačítkem Zpět z platební brány (bfcache) znovu odemknout formulář.
        window.addEventListener('pageshow', function (event) {
            if (event.persisted) setBusy(false);
        });

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            show(formError, false);
            show(emailError, false);

            const first = email.value.trim();
            const second = email2.value.trim();
            if (!first || !email.checkValidity()) {
                emailError.textContent = ERROR_MESSAGES.INVALID_EMAIL;
                show(emailError, true);
                email.focus();
                return;
            }
            if (first.toLowerCase() !== second.toLowerCase()) {
                emailError.textContent = 'E-mailové adresy se neshodují. Zkontrolujte překlep.';
                show(emailError, true);
                email2.focus();
                return;
            }
            if (!terms.checked) {
                showFormError(ERROR_MESSAGES.TERMS_NOT_ACCEPTED);
                terms.focus();
                return;
            }
            if (!termsVersion) {
                showFormError('Nabídka ještě není načtená. Zkuste to prosím za chvíli.');
                return;
            }

            setBusy(true);
            request('/api/public/checkout-sessions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({
                    offerId: offerId,
                    email: first,
                    termsAccepted: true,
                    termsVersion: termsVersion
                })
            }).then(function (result) {
                if (result.ok && result.data && result.data.checkoutUrl) {
                    window.location.assign(result.data.checkoutUrl);
                    return;
                }
                const code = result.data && result.data.error;
                showFormError(ERROR_MESSAGES[code] || GENERIC_ERROR);
                setBusy(false);
                if (code === 'TERMS_OUTDATED') {
                    terms.checked = false;
                    loadOffers().then(applyOffers).catch(function () {});
                }
            }).catch(function () {
                showFormError(NETWORK_ERROR);
                setBusy(false);
            });
        });
    }

    // --- order-success.html --------------------------------------------------------------------

    function initOrderStatus() {
        const sessionId = new URLSearchParams(window.location.search).get('session_id');
        const spinner = byId('status-spinner');
        const icon = byId('status-icon');
        const eyebrow = byId('status-eyebrow');
        const title = byId('status-title');
        const text = byId('status-text');
        const refBox = byId('order-ref');
        const actions = byId('status-actions');
        const retry = byId('status-retry');
        const startedAt = Date.now();
        let timer = null;

        function render(state) {
            show(spinner, state.spinner === true);
            icon.textContent = state.icon || '';
            show(icon, Boolean(state.icon));
            eyebrow.textContent = state.eyebrow;
            title.textContent = state.title;
            text.textContent = state.text;
            if (state.orderRef) {
                byId('order-ref-value').textContent = state.orderRef;
            }
            show(refBox, Boolean(state.orderRef));
            if (!state.voucherShown) showVoucher(null);
            show(actions, state.final === true);
            show(retry, state.retry === true);
        }

        function showVoucher(voucher) {
            const card = byId('voucher-card');
            if (!voucher) {
                show(card, false);
                return;
            }
            byId('voucher-amount').textContent = formatCzk(voucher.amountCzk);
            byId('voucher-qr').src = voucher.qrDataUri;
            byId('voucher-code').textContent = voucher.code;
            const valid = new Date(voucher.validUntil + 'T00:00:00');
            byId('voucher-valid').textContent = isNaN(valid.getTime())
                ? voucher.validUntil
                : valid.toLocaleDateString('cs-CZ', { day: 'numeric', month: 'numeric', year: 'numeric' }).replace(/\s/g, NBSP);
            show(card, true);
        }

        const printButton = byId('voucher-print');
        if (printButton) printButton.addEventListener('click', function () { window.print(); });

        function waiting(orderRef) {
            render({
                spinner: true,
                eyebrow: 'Objednávka',
                title: 'Čekáme na potvrzení platby…',
                text: 'Stránku prosím nezavírejte, potvrzení obvykle trvá několik sekund.',
                orderRef: orderRef
            });
        }

        function timedOut(orderRef) {
            render({
                icon: '…',
                eyebrow: 'Zatím bez potvrzení',
                title: 'Potvrzení zatím nedorazilo.',
                text: 'Voucher vám přijde e-mailem, jakmile platbu potvrdíme. Pokud do 30 minut nedorazí, napište nám s číslem objednávky.',
                orderRef: orderRef,
                final: true
            });
        }

        function handle(data) {
            switch (data.status) {
                case 'PAID': {
                    let message;
                    if (data.emailStatus === 'FAILED') {
                        message = 'Platba proběhla v pořádku, ale e-mail s voucherem se nám zatím nepodařilo odeslat. Ozveme se vám, případně nám napište s číslem objednávky.';
                    } else if (data.emailStatus === 'SENT') {
                        message = 'Voucher jsme poslali na ' + data.emailMasked + '. Mrkněte i do složky Hromadné nebo Spam.';
                    } else {
                        message = 'Voucher právě odesíláme na ' + data.emailMasked + ', obvykle dorazí do minuty. Mrkněte i do složky Hromadné nebo Spam.';
                    }
                    render({
                        icon: data.voucher ? '' : '✓',
                        eyebrow: 'Děkujeme!',
                        title: data.emailStatus === 'FAILED' ? 'Platba proběhla.' : 'Hotovo.',
                        text: data.voucher ? 'Váš voucher je níže. ' + message : message,
                        orderRef: data.orderRef,
                        final: true,
                        voucherShown: Boolean(data.voucher)
                    });
                    showVoucher(data.voucher);
                    return true;
                }
                case 'FAILED':
                case 'EXPIRED':
                    render({
                        icon: '✕',
                        eyebrow: 'Objednávka',
                        title: 'Platba neproběhla.',
                        text: 'Z vaší karty nebylo nic strženo. Můžete to zkusit znovu.',
                        orderRef: data.orderRef,
                        final: true,
                        retry: true
                    });
                    return true;
                case 'PENDING':
                case 'PROCESSING':
                    waiting(data.orderRef);
                    return false;
                default:
                    return false; // UNKNOWN – zkoušíme dál do vypršení času
            }
        }

        function nextDelay() {
            const elapsed = Date.now() - startedAt;
            if (elapsed < 30000) return 2000;
            if (elapsed < 120000) return 5000;
            return null;
        }

        function poll() {
            request('/api/public/orders/status?session_id=' + encodeURIComponent(sessionId), {
                headers: { Accept: 'application/json' }
            }).then(function (result) {
                if (result.ok && result.data && handle(result.data)) return;
                schedule(result.data && result.data.orderRef);
            }).catch(function () {
                schedule(null);
            });
        }

        function schedule(orderRef) {
            const delay = nextDelay();
            if (delay === null) {
                timedOut(orderRef);
                return;
            }
            timer = window.setTimeout(poll, delay);
        }

        if (!sessionId) {
            render({
                icon: '?',
                eyebrow: 'Objednávka',
                title: 'Chybí údaje o objednávce.',
                text: 'Tuto stránku otevřete odkazem po platbě. Pokud jste zaplatili, voucher vám přijde e-mailem.',
                final: true,
                retry: true
            });
            return;
        }

        waiting(null);
        poll();
        window.addEventListener('pagehide', function () {
            if (timer) window.clearTimeout(timer);
        });
    }

    const page = document.body.dataset.page;
    if (page === 'offers') initOffers();
    if (page === 'checkout') initCheckout();
    if (page === 'order-status') initOrderStatus();
})();
