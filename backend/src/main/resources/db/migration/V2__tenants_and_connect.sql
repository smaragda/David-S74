-- Více podniků (tenantů) a Stripe Connect – viz docs/vouchery-vice-klientu-connect.md.
-- Stávající data patří výchozímu tenantovi S74 (pevné ID), který dál platí přes platformní Stripe účet (payment_mode = PLATFORM).

create table tenant (
    id                          uuid primary key,
    slug                        varchar(40)  not null unique
        check (slug ~ '^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$'),
    display_name                varchar(120) not null,
    legal_name                  varchar(200),
    company_id                  varchar(20),                     -- IČO
    address                     varchar(255),
    contact_email               varchar(254),
    reply_to                    varchar(254),
    status                      varchar(16)  not null check (status in ('ACTIVE', 'SUSPENDED')),
    payment_mode                varchar(16)  not null check (payment_mode in ('PLATFORM', 'CONNECT')),
    stripe_account_id           varchar(64)  unique,
    stripe_card_payments        varchar(24),                     -- active | pending | restricted | unsupported | disconnected
    stripe_requirements_due     int          not null default 0,
    stripe_status_checked_at    timestamptz,
    frontend_base_url           varchar(255),                    -- vlastní web klienta; prázdné = hostované stránky
    cors_allowed_origins        varchar(1000),                   -- čárkou oddělené originy pro vložitelný skript
    terms_url                   varchar(255),                    -- prázdné = generované podmínky na hostované stránce
    terms_version               varchar(32),
    validity_months             int          check (validity_months between 1 and 120),
    show_on_return_minutes      int          check (show_on_return_minutes between 0 and 1440),
    brand_color                 varchar(7)   check (brand_color ~ '^#[0-9a-fA-F]{6}$'),
    application_fee_bps         int          not null default 0 check (application_fee_bps between 0 and 2000),
    application_fee_fixed_minor bigint       not null default 0 check (application_fee_fixed_minor >= 0),
    created_at                  timestamptz  not null,
    updated_at                  timestamptz  not null
);

create table tenant_offer (
    id            uuid primary key,
    tenant_id     uuid        not null references tenant (id),
    offer_code    varchar(32) not null,
    amount_minor  bigint      not null check (amount_minor > 0),
    sort_order    int         not null default 0,
    active        boolean     not null default true,
    unique (tenant_id, offer_code)
);

-- Výchozí tenant: hodnoty null se berou z konfigurace aplikace (s74.frontend-base-url, s74.voucher.*),
-- takže dosavadní nasazení S74 funguje beze změny.
insert into tenant (id, slug, display_name, legal_name, company_id, address, contact_email, reply_to, status,
                    payment_mode, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000001', 's74', 'S74 wine&café', 'S74 wine&café, s.r.o.', '23035111',
        'Slezská 856/74, Praha 3', 's74winecafe@seznam.cz', null, 'ACTIVE', 'PLATFORM', now(), now());

-- Objednávky -----------------------------------------------------------------------------------
alter table voucher_order add column tenant_id uuid references tenant (id);
alter table voucher_order add column stripe_account_id varchar(64);   -- účet, na kterém vznikla platba (null = platforma)
alter table voucher_order add column application_fee_minor bigint not null default 0;
update voucher_order set tenant_id = '00000000-0000-0000-0000-000000000001';
alter table voucher_order alter column tenant_id set not null;
create index voucher_order_tenant_idx on voucher_order (tenant_id, created_at);
drop index voucher_order_attention_idx;
create index voucher_order_attention_idx on voucher_order (tenant_id, attention_required) where attention_required;

-- Vouchery (kód zůstává globálně unikátní – QR vede na /staff/v/{kód} bez identifikace podniku) ---
alter table voucher add column tenant_id uuid references tenant (id);
update voucher v set tenant_id = o.tenant_id from voucher_order o where o.id = v.order_id;
alter table voucher alter column tenant_id set not null;
create index voucher_tenant_idx on voucher (tenant_id);

-- Uživatelé: obsluha a vedoucí patří právě jednomu podniku, správce platformy žádnému ----------
alter table staff_user add column tenant_id uuid references tenant (id);
update staff_user set tenant_id = '00000000-0000-0000-0000-000000000001';
alter table staff_user drop constraint staff_user_role_check;
alter table staff_user add constraint staff_user_role_check check (role in ('STAFF', 'MANAGER', 'PLATFORM_ADMIN'));
alter table staff_user add constraint staff_user_tenant_check
    check ((role = 'PLATFORM_ADMIN') = (tenant_id is null));
create index staff_user_tenant_idx on staff_user (tenant_id);

-- Audit -----------------------------------------------------------------------------------------
alter table audit_log add column tenant_id uuid references tenant (id);
update audit_log a set tenant_id = '00000000-0000-0000-0000-000000000001';
create index audit_log_tenant_idx on audit_log (tenant_id, occurred_at);

-- Stripe události: z jakého účtu přišly (null = platforma); v2 typy jsou delší ------------------
alter table stripe_event add column account varchar(64);
alter table stripe_event alter column type type varchar(128);
