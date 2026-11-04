create table staff_user (
    id              uuid primary key,
    username        varchar(64)  not null unique,
    display_name    varchar(128) not null,
    password_hash   varchar(255) not null,
    role            varchar(16)  not null check (role in ('STAFF', 'MANAGER')),
    enabled         boolean      not null default true,
    created_at      timestamptz  not null,
    disabled_at     timestamptz
);

create table voucher_order (
    id                          uuid primary key,
    offer_id                    varchar(32)  not null,
    amount_minor                bigint       not null check (amount_minor > 0),
    currency                    char(3)      not null default 'CZK',
    customer_email              varchar(254) not null,
    terms_version               varchar(32)  not null,
    terms_accepted_at           timestamptz  not null,
    status                      varchar(24)  not null check (status in
        ('PENDING_PAYMENT','PAYMENT_PROCESSING','PAID','FAILED','EXPIRED','REFUNDED','DISPUTED')),
    stripe_checkout_session_id  varchar(255) unique,
    stripe_payment_intent_id    varchar(255) unique,
    refunded_amount_minor       bigint       not null default 0,
    attention_required          boolean      not null default false,
    attention_reason            varchar(255),
    created_at                  timestamptz  not null,
    updated_at                  timestamptz  not null,
    paid_at                     timestamptz
);
create index voucher_order_email_idx on voucher_order (lower(customer_email));
create index voucher_order_attention_idx on voucher_order (attention_required) where attention_required;

create table voucher (
    id               uuid primary key,
    order_id         uuid         not null unique references voucher_order (id),
    code             varchar(16)  not null unique,   -- kanonický tvar bez pomlček
    amount_minor     bigint       not null,
    currency         char(3)      not null,
    issued_at        timestamptz  not null,
    valid_until      date         not null,
    status           varchar(16)  not null check (status in ('ACTIVE','REDEEMED','CANCELLED')),
    redeemed_at      timestamptz,
    redeemed_by      uuid references staff_user (id),
    redemption_note  varchar(255),                   -- číslo účtenky apod., volitelné
    cancelled_at     timestamptz,
    cancelled_by     uuid references staff_user (id),
    cancel_reason    varchar(255),
    version          bigint       not null default 0
);

create table email_delivery (
    id                   uuid primary key,
    voucher_id           uuid         not null references voucher (id),
    kind                 varchar(16)  not null check (kind in ('ISSUED','RESEND')),
    recipient            varchar(254) not null,
    status               varchar(16)  not null check (status in ('PENDING','SENDING','SENT','FAILED')),
    attempts             int          not null default 0,
    next_attempt_at      timestamptz  not null,
    last_error           varchar(500),               -- bez tajných údajů
    provider_message_id  varchar(255),
    requested_by         uuid references staff_user (id),
    created_at           timestamptz  not null,
    sent_at              timestamptz
);
create index email_delivery_due_idx on email_delivery (status, next_attempt_at);

create table stripe_event (
    event_id      varchar(255) primary key,
    type          varchar(64)  not null,
    livemode      boolean      not null,
    status        varchar(16)  not null check (status in ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    error         varchar(500),
    received_at   timestamptz  not null,
    processed_at  timestamptz
);

create table audit_log (
    id           bigserial primary key,
    occurred_at  timestamptz not null,
    actor_type   varchar(8)  not null check (actor_type in ('STAFF','SYSTEM','STRIPE')),
    actor_id     uuid references staff_user (id),
    action       varchar(48) not null,
    voucher_id   uuid references voucher (id),
    order_id     uuid references voucher_order (id),
    ip           inet,
    details      jsonb       -- nikdy hesla, tajné klíče ani celý voucherový kód
);
create index audit_log_voucher_idx on audit_log (voucher_id, occurred_at);
create index audit_log_order_idx on audit_log (order_id, occurred_at);
