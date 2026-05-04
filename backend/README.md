# s74 Voucher Backend

Spring Boot backend skeleton for the s74.cz voucher system. The static site remains outside this app; this backend will own voucher orders, Stripe fulfillment, voucher delivery, staff validation, and one-time redemption.

## Stack

- Java 21
- Maven
- Spring Boot 4.0.6
- PostgreSQL
- Flyway
- Stripe Java 32.1.0

## MVP Rules

- Sell fixed voucher amounts: 500 CZK, 1000 CZK, and 2000 CZK.
- Use Stripe-hosted Checkout Sessions for one-time payments.
- Create a voucher only after a confirmed Stripe webhook.
- Deliver the voucher by email and make it available on a status/detail page.
- Redeem each voucher exactly once. Any unused amount is not tracked in MVP.

## Planned API Surface

The endpoints below are intentionally not implemented yet. They document the first implementation target.

- `POST /api/checkout/sessions` - create a Stripe Checkout Session for a selected voucher amount and customer email.
- `POST /api/stripe/webhook` - verify Stripe signature and fulfill completed Checkout Sessions.
- `GET /api/vouchers/{publicToken}` - return voucher detail for the customer-facing page.
- `POST /api/staff/login` - authenticate staff before voucher validation.
- `POST /api/staff/vouchers/verify` - verify voucher code or QR payload without redeeming it.
- `POST /api/staff/vouchers/redeem` - mark a valid voucher as redeemed exactly once.

## Environment

Required for production:

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `STRIPE_API_KEY`
- `STRIPE_WEBHOOK_SECRET`
- `S74_FRONTEND_BASE_URL`
- `SMTP_HOST`
- `SMTP_PORT`
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- `S74_MAIL_FROM`

Useful local defaults are defined in `src/main/resources/application-local.yml`.

## Commands

```bash
mvn test
```

```bash
mvn spring-boot:run
```

`mvn spring-boot:run` expects a local PostgreSQL database by default: `jdbc:postgresql://localhost:5432/s74_vouchers` with user/password `s74_vouchers`.

## Next Implementation Step

Add the first domain model and migration for orders, vouchers, Stripe event idempotency, and redemption audit. Then wire the Checkout Session creation endpoint before implementing webhook fulfillment.
