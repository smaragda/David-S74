#!/usr/bin/env python3
"""
Lokální atrapa Stripe pro vývoj a ukázky bez Stripe účtu (jen standardní knihovna Pythonu 3.10+).

Pokrývá přesně to, co backend voucherů volá:
  POST /v2/core/accounts            – připojený účet podniku (Accounts v2)
  GET  /v2/core/accounts/{id}       – stav (configuration.merchant.capabilities.card_payments, requirements)
  POST /v2/core/account_links       – odkaz na „onboarding“ (stránka atrapy s tlačítkem Dokončit)
  POST /v1/checkout/sessions        – Checkout Session (na platformě nebo s hlavičkou Stripe-Account)
  GET  /v1/checkout/sessions/{id}   – stav session
  GET  /v1/payment_intents/{id}     – metadata PaymentIntentu
a navíc HTML stránky /mock/checkout/{id} a /mock/onboarding/{acct}, které po kliknutí pošlou do aplikace
podepsaný webhook (checkout.session.completed, account.updated) – stejně jako skutečný Stripe.

Spuštění:  python3 stripe_mock.py   (nebo docker compose --profile mock up -d stripe-mock)
Proměnné:  MOCK_PORT (12111), MOCK_PUBLIC_URL (http://127.0.0.1:12111), APP_URL (http://127.0.0.1:8080),
           STRIPE_WEBHOOK_SECRET, STRIPE_CONNECT_WEBHOOK_SECRET (musí odpovídat .env aplikace).
NIKDY nepoužívat v produkci – aplikace s S74_STRIPE_API_BASE a STRIPE_LIVEMODE=true ani nenastartuje.
"""
import hashlib
import hmac
import html
import json
import os
import secrets
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "12111"))
PUBLIC_URL = os.environ.get("MOCK_PUBLIC_URL", f"http://127.0.0.1:{PORT}").rstrip("/")
APP_URL = os.environ.get("APP_URL", "http://127.0.0.1:8080").rstrip("/")
PLATFORM_SECRET = os.environ.get("STRIPE_WEBHOOK_SECRET", "whsec_mock_platform")
CONNECT_SECRET = os.environ.get("STRIPE_CONNECT_WEBHOOK_SECRET", "whsec_mock_connect")
API_VERSION = "2026-09-30.endive"

LOCK = threading.Lock()
ACCOUNTS = {}   # acct_id -> dict
LINKS = {}      # acct_id -> {refresh_url, return_url}
SESSIONS = {}   # cs_id -> dict (+ "_account")
INTENTS = {}    # pi_id -> dict (+ "_account")


def rid(prefix):
    return prefix + "_mock_" + secrets.token_hex(8)


def nested_form(body):
    """application/x-www-form-urlencoded s klíči a[b][c] → vnořený dict (pole jako dict s číselnými klíči)."""
    out = {}
    for key, values in urllib.parse.parse_qs(body, keep_blank_values=True).items():
        parts = key.replace("]", "").split("[")
        node = out
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = values[-1]
    return out


def sign(payload, secret):
    t = int(time.time())
    sig = hmac.new(secret.encode(), f"{t}.{payload}".encode(), hashlib.sha256).hexdigest()
    return f"t={t},v1={sig}"


def send_event(event_type, obj, account=None):
    event = {
        "id": rid("evt"),
        "object": "event",
        "api_version": API_VERSION,
        "created": int(time.time()),
        "livemode": False,
        "type": event_type,
        "data": {"object": obj},
    }
    path, secret = "/api/stripe/webhook", PLATFORM_SECRET
    if account:
        event["account"] = account
        path, secret = "/api/stripe/connect-webhook", CONNECT_SECRET
    payload = json.dumps(event)
    req = urllib.request.Request(
        APP_URL + path,
        data=payload.encode(),
        headers={"Content-Type": "application/json", "Stripe-Signature": sign(payload, secret)},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            print(f"[mock] webhook {event_type} → {path}: {r.status}", flush=True)
    except Exception as e:  # noqa: BLE001 – atrapa jen loguje
        print(f"[mock] webhook {event_type} → {path} failed: {e}", flush=True)


def account_json(acct):
    a = ACCOUNTS[acct]
    entries = [] if a["card_payments"] == "active" else [
        {"awaiting_action_from": "user", "description": "business details (mock)"}]
    return {
        "id": acct,
        "object": "v2.core.account",
        "closed": False,
        "display_name": a["display_name"],
        "contact_email": a.get("contact_email"),
        "dashboard": "full",
        "livemode": False,
        "metadata": a.get("metadata", {}),
        "configuration": {"merchant": {"applied": True, "capabilities": {
            "card_payments": {"status": a["card_payments"], "status_details": []}}}},
        "requirements": {"entries": entries, "summary": {}},
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "stripe-mock-s74/1"

    def log_message(self, fmt, *args):
        print("[mock] " + (fmt % args), flush=True)

    # --- helpers ---------------------------------------------------------------------------
    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode() if n else ""

    def send_json(self, obj, status=200):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Request-Id", rid("req"))
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def not_found(self, what):
        self.send_json({"error": {"type": "invalid_request_error", "code": "resource_missing",
                                  "message": f"No such {what}"}}, 404)

    def send_html(self, body, status=200):
        data = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                "<style>body{font-family:system-ui;max-width:520px;margin:40px auto;padding:0 16px}"
                "button{font-size:1.1rem;padding:12px 20px;margin:6px 6px 6px 0}"
                ".tag{background:#635bff;color:#fff;padding:2px 8px;border-radius:6px}</style>" + body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def redirect(self, url):
        self.send_response(303)
        self.send_header("Location", url)
        self.end_headers()

    # --- routing ---------------------------------------------------------------------------
    def do_GET(self):  # noqa: N802
        url = urllib.parse.urlparse(self.path)
        path = url.path
        account = self.headers.get("Stripe-Account")
        with LOCK:
            if path.startswith("/v2/core/accounts/"):
                acct = path.rsplit("/", 1)[1]
                return self.send_json(account_json(acct)) if acct in ACCOUNTS else self.not_found("account")
            if path.startswith("/v1/checkout/sessions/"):
                s = SESSIONS.get(path.rsplit("/", 1)[1])
                if not s or s["_account"] != account:
                    return self.not_found("checkout.session")
                return self.send_json({k: v for k, v in s.items() if not k.startswith("_")})
            if path.startswith("/v1/payment_intents/"):
                pi = INTENTS.get(path.rsplit("/", 1)[1])
                if not pi or pi["_account"] != account:
                    return self.not_found("payment_intent")
                return self.send_json({k: v for k, v in pi.items() if not k.startswith("_")})
            if path.startswith("/mock/checkout/"):
                s = SESSIONS.get(path.rsplit("/", 1)[1])
                if not s:
                    return self.send_html("<h1>Session nenalezena</h1>", 404)
                fee = s.get("_fee")
                return self.send_html(
                    "<p><span class=tag>Stripe MOCK</span> testovací platba, žádné peníze</p>"
                    f"<h1>{html.escape(s['_name'])}</h1>"
                    f"<p>Částka: <b>{s['amount_total'] / 100:.0f} Kč</b><br>"
                    f"E-mail: {html.escape(s.get('customer_email') or '')}<br>"
                    f"Účet prodávajícího: <code>{html.escape(s['_account'] or 'platforma')}</code>"
                    + (f"<br>Provize platformy: {int(fee) / 100:.2f} Kč" if fee else "") + "</p>"
                    f"<form method=post action='/mock/checkout/{s['id']}/pay'><button>Zaplatit</button></form>"
                    f"<form method=get action='{html.escape(s['cancel_url'])}'><button>Zrušit</button></form>")
            if path.startswith("/mock/onboarding/"):
                acct = path.rsplit("/", 1)[1]
                if acct not in ACCOUNTS:
                    return self.send_html("<h1>Účet nenalezen</h1>", 404)
                return self.send_html(
                    "<p><span class=tag>Stripe MOCK</span> onboarding připojeného účtu</p>"
                    f"<h1>{html.escape(ACCOUNTS[acct]['display_name'])}</h1>"
                    f"<p>Účet <code>{acct}</code>, stav karet: <b>{ACCOUNTS[acct]['card_payments']}</b></p>"
                    f"<form method=post action='/mock/onboarding/{acct}/complete'><button>Dokončit onboarding</button></form>")
        return self.not_found("route")

    def do_POST(self):  # noqa: N802
        path = urllib.parse.urlparse(self.path).path
        account = self.headers.get("Stripe-Account")
        raw = self.body()
        with LOCK:
            if path == "/v2/core/accounts":
                data = json.loads(raw or "{}")
                acct = "acct_mock_" + secrets.token_hex(6)
                ACCOUNTS[acct] = {"display_name": data.get("display_name", "?"),
                                  "contact_email": data.get("contact_email"),
                                  "metadata": data.get("metadata", {}), "card_payments": "pending"}
                return self.send_json(account_json(acct))
            if path == "/v2/core/account_links":
                data = json.loads(raw or "{}")
                acct = data.get("account")
                if acct not in ACCOUNTS:
                    return self.not_found("account")
                ob = data.get("use_case", {}).get("account_onboarding", {})
                LINKS[acct] = {"refresh_url": ob.get("refresh_url"), "return_url": ob.get("return_url")}
                return self.send_json({"object": "v2.core.account_link", "account": acct, "livemode": False,
                                       "url": f"{PUBLIC_URL}/mock/onboarding/{acct}"})
            if path == "/v1/checkout/sessions":
                if account and ACCOUNTS.get(account, {}).get("card_payments") != "active":
                    return self.send_json({"error": {"type": "invalid_request_error",
                                                     "code": "account_invalid",
                                                     "message": "Connected account cannot accept payments yet"}},
                                          400)
                f = nested_form(raw)
                item = f.get("line_items", {}).get("0", {})
                price = item.get("price_data", {})
                cs = "cs_test_mock_" + secrets.token_hex(8)
                SESSIONS[cs] = {
                    "id": cs, "object": "checkout.session", "mode": f.get("mode", "payment"),
                    "currency": price.get("currency", "czk"),
                    "amount_total": int(price.get("unit_amount", "0")) * int(item.get("quantity", "1")),
                    "payment_status": "unpaid", "status": "open", "payment_intent": None,
                    "customer_email": f.get("customer_email"), "metadata": f.get("metadata", {}),
                    "success_url": f.get("success_url"), "cancel_url": f.get("cancel_url"),
                    "url": f"{PUBLIC_URL}/mock/checkout/{cs}",
                    "_account": account, "_name": price.get("product_data", {}).get("name", "Voucher"),
                    "_fee": f.get("payment_intent_data", {}).get("application_fee_amount"),
                    "_pi_metadata": f.get("payment_intent_data", {}).get("metadata", {}),
                }
                return self.send_json({k: v for k, v in SESSIONS[cs].items() if not k.startswith("_")})
            if path.startswith("/mock/checkout/") and path.endswith("/pay"):
                cs = path.split("/")[3]
                s = SESSIONS.get(cs)
                if not s:
                    return self.send_html("<h1>Session nenalezena</h1>", 404)
                pi = "pi_mock_" + secrets.token_hex(8)
                INTENTS[pi] = {"id": pi, "object": "payment_intent", "amount": s["amount_total"],
                               "metadata": s["_pi_metadata"], "_account": s["_account"]}
                s.update(payment_status="paid", status="complete", payment_intent=pi)
                obj = {k: v for k, v in s.items() if not k.startswith("_")}
                target = s["success_url"].replace("{CHECKOUT_SESSION_ID}", cs)
                acct = s["_account"]
            elif path.startswith("/mock/onboarding/") and path.endswith("/complete"):
                acct = path.split("/")[3]
                if acct not in ACCOUNTS:
                    return self.send_html("<h1>Účet nenalezen</h1>", 404)
                ACCOUNTS[acct]["card_payments"] = "active"
                target = (LINKS.get(acct) or {}).get("return_url") or PUBLIC_URL
                obj = {"id": acct, "object": "account", "charges_enabled": True}
                cs = None
            else:
                return self.not_found("route")
        # webhook mimo zámek (aplikace si mezitím čte stav z atrapy)
        if cs:
            send_event("checkout.session.completed", obj, acct)
        else:
            send_event("account.updated", obj, acct)
        return self.redirect(target)


if __name__ == "__main__":
    print(f"[mock] Stripe mock on :{PORT}, public {PUBLIC_URL}, app {APP_URL}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
