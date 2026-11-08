package cz.s74.vouchers.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.admin.ExportService;
import cz.s74.vouchers.connect.ConnectGateway;
import cz.s74.vouchers.connect.ConnectService;
import cz.s74.vouchers.platform.TenantAdminService;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.support.AbstractIT;
import cz.s74.vouchers.voucher.Voucher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Více podniků a Stripe Connect: založení podniku, onboarding, přímé platby na připojený účet, směrování webhooků
 * podle účtu a hlavně izolace dat mezi podniky (obsluha, vedoucí, export, veřejné API).
 */
class MultiTenantConnectTest extends AbstractIT {

    @Autowired TenantAdminService tenantAdmin;
    @Autowired ConnectService connect;
    @Autowired TenantRepository tenants;
    @Autowired ExportService export;

    private StaffUser platformAdmin;

    @BeforeEach
    void setUp() {
        platformAdmin = createStaff("platform", "platform-password-1", StaffRole.PLATFORM_ADMIN);
    }

    private Tenant newTenant(String slug, String offers) {
        return tenantAdmin.create(platformAdmin.getId(), new TenantAdminService.NewTenant(
                slug, "Kavárna " + slug, slug + " s.r.o.", "12345678", "Dlouhá 1, Praha", "info@" + slug + ".test", offers),
                "127.0.0.1");
    }

    /** Onboarding + Stripe hlásí aktivní card_payments → podnik smí prodávat. Vrací acct_… */
    private String connectAndActivate(UUID tenantId) {
        connect.onboardingLink(tenantId, platformAdmin.getId(), "127.0.0.1", "https://x/refresh", "https://x/return");
        String account = tenants.findById(tenantId).orElseThrow().getStripeAccountId();
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("active", 0, false));
        connect.refresh(tenantId);
        return account;
    }

    private int checkoutAt(String slug, String offerId, String email) throws Exception {
        return mvc.perform(post("/api/public/t/" + slug + "/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + offerId + "\",\"email\":\"" + email
                                + "\",\"termsAccepted\":true,\"termsVersion\":\"2026-11\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** Nákup u podniku na jeho připojeném účtu + webhook z Connect endpointu; vrátí vystavený voucher. */
    private Voucher buyAt(String slug, String account, String offerId, String email) throws Exception {
        assertThat(checkoutAt(slug, offerId, email)).isEqualTo(201);
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        assertThat(sendConnectWebhook(account, "checkout.session.completed", "{\"id\":\"" + sid + "\"}"))
                .isEqualTo(200);
        UUID orderId = jdbc.queryForObject(
                "select id from voucher_order where stripe_checkout_session_id = ?", UUID.class, sid);
        return vouchers.findAll().stream().filter(v -> v.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private RequestPostProcessor as(StaffUser u) {
        return user(new StaffPrincipal(u));
    }

    // --- založení a onboarding --------------------------------------------------------------

    @Test
    void platformAdminCreatesTenantWhichCannotSellBeforeOnboarding() throws Exception {
        mvc.perform(post("/platform/tenants").with(as(platformAdmin)).with(csrf())
                        .param("slug", "u-lipy").param("displayName", "U Lípy").param("legalName", "U Lípy s.r.o.")
                        .param("companyId", "87654321").param("contactEmail", "info@ulipy.test")
                        .param("offers", "300, 600"))
                .andExpect(status().is3xxRedirection());
        Tenant t = tenants.findBySlug("u-lipy").orElseThrow();
        assertThat(t.getPaymentMode()).isEqualTo(Tenant.PaymentMode.CONNECT);
        assertThat(t.getStripeAccountId()).isNull();

        mvc.perform(get("/api/public/t/u-lipy/offers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesEnabled").value(false))
                .andExpect(jsonPath("$.tenant.name").value("U Lípy"))
                .andExpect(jsonPath("$.offers[0].id").value("CZK_300"))
                .andExpect(jsonPath("$.offers[1].amountCzk").value(600));
        assertThat(checkoutAt("u-lipy", "CZK_300", "jana@example.com")).isEqualTo(409);
        assertThat(count("voucher_order")).isZero();
        assertThat(gateway.requests).isEmpty();
    }

    @Test
    void invalidTenantInputIsRejected() throws Exception {
        MvcResult r = mvc.perform(post("/platform/tenants").with(as(platformAdmin)).with(csrf())
                        .param("slug", "Admin").param("displayName", "X").param("contactEmail", "x@x.test")
                        .param("offers", "500"))
                .andReturn();
        assertThat(r.getFlashMap().get("error").toString()).contains("slug");
        r = mvc.perform(post("/platform/tenants").with(as(platformAdmin)).with(csrf())
                        .param("slug", "ok").param("displayName", "X").param("contactEmail", "x@x.test")
                        .param("offers", "10"))
                .andReturn();
        assertThat(r.getFlashMap().get("error").toString()).contains("50–100000");
        assertThat(tenants.count()).isEqualTo(1);
    }

    @Test
    void managerCompletesStripeOnboardingAndSalesStartWhenCardPaymentsActive() throws Exception {
        Tenant t = newTenant("cafe", "500");
        StaffUser boss = createStaff(t.getId(), "cafe-boss", "cafe-password-12", StaffRole.MANAGER);

        MvcResult onboard = mvc.perform(post("/admin/stripe/onboard").with(as(boss)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(onboard.getResponse().getRedirectedUrl()).startsWith("https://connect.stripe.test/setup/acct_fake_");
        assertThat(onboard.getResponse().getRedirectedUrl()).contains("/admin/stripe/refresh-link", "/admin/stripe/return");
        assertThat(connectGateway.created).hasSize(1);
        assertThat(connectGateway.created.get(0).country()).isEqualTo("CZ");
        String account = tenants.findById(t.getId()).orElseThrow().getStripeAccountId();
        assertThat(account).startsWith("acct_fake_");

        // Druhé otevření onboardingu nezakládá nový účet.
        mvc.perform(get("/admin/stripe/refresh-link").with(as(boss))).andExpect(status().is3xxRedirection());
        assertThat(connectGateway.created).hasSize(1);

        // Návrat z onboardingu – Stripe ještě ověřuje.
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("pending", 1, false));
        mvc.perform(get("/admin/stripe/return").with(as(boss))).andExpect(redirectedUrl("/admin/stripe"));
        assertThat(tenants.findById(t.getId()).orElseThrow().salesEnabled()).isFalse();

        // Stripe účet aktivuje → webhook account.updated z Connect endpointu → prodej běží.
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("active", 0, false));
        assertThat(sendConnectWebhook(account, "account.updated", "{\"id\":\"" + account + "\"}")).isEqualTo(200);
        assertThat(tenants.findById(t.getId()).orElseThrow().salesEnabled()).isTrue();
        mvc.perform(get("/admin/stripe").with(as(boss)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(account)))
                .andExpect(content().string(containsString("Aktivní")));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'STRIPE_STATUS_CHANGED'"
                + " and tenant_id = ?", Integer.class, t.getId())).isEqualTo(2);
    }

    @Test
    void accountEventsEndpointRefreshesStatusFromThinEvent() throws Exception {
        Tenant t = newTenant("thin", "500");
        connect.onboardingLink(t.getId(), platformAdmin.getId(), "127.0.0.1", "https://x/r", "https://x/s");
        String account = tenants.findById(t.getId()).orElseThrow().getStripeAccountId();
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("active", 0, false));

        String payload = "{\"id\":\"evt_thin_1\",\"object\":\"v2.core.event\","
                + "\"type\":\"v2.core.account[configuration.merchant].capability_status_updated\",\"livemode\":false,"
                + "\"created\":\"2026-11-08T10:00:00.000Z\",\"related_object\":{\"id\":\"" + account
                + "\",\"type\":\"v2.core.account\",\"url\":\"/v2/core/accounts/" + account + "\"}}";
        mvc.perform(post("/api/stripe/account-events").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, "whsec_wrong", Instant.now()))
                        .content(payload))
                .andExpect(status().isBadRequest());
        assertThat(tenants.findById(t.getId()).orElseThrow().salesEnabled()).isFalse();

        mvc.perform(post("/api/stripe/account-events").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, ACCOUNT_EVENTS_SECRET, Instant.now()))
                        .content(payload))
                .andExpect(status().isOk());
        assertThat(tenants.findById(t.getId()).orElseThrow().salesEnabled()).isTrue();
        assertThat(jdbc.queryForObject("select status from stripe_event where event_id = 'evt_thin_1'", String.class))
                .isEqualTo("PROCESSED");
    }

    @Test
    void deauthorizationStopsSales() throws Exception {
        Tenant t = newTenant("bye", "500");
        String account = connectAndActivate(t.getId());
        assertThat(sendConnectWebhook(account, "account.application.deauthorized", "{\"id\":\"ca_123\"}"))
                .isEqualTo(200);
        assertThat(tenants.findById(t.getId()).orElseThrow().getStripeCardPayments()).isEqualTo("disconnected");
        assertThat(checkoutAt("bye", "CZK_500", "jana@example.com")).isEqualTo(409);
    }

    // --- přímé platby na připojený účet -----------------------------------------------------

    @Test
    void directChargeOnConnectedAccountWithApplicationFee() throws Exception {
        Tenant t = newTenant("vinarna", "1000");
        tenantAdmin.updateFees(platformAdmin.getId(), t.getId(), 250, 5, "127.0.0.1"); // 2,5 % + 5 Kč
        tenantAdmin.updateSales(platformAdmin.getId(), t.getId(), new TenantAdminService.Sales(
                null, null, null, null, 6, null, "#335577"), "127.0.0.1");
        String account = connectAndActivate(t.getId());

        Voucher v = buyAt("vinarna", account, "CZK_1000", "petr@example.com");

        CheckoutGateway.SessionRequest req = gateway.lastRequest();
        assertThat(req.stripeAccountId()).isEqualTo(account);
        assertThat(req.tenantId()).isEqualTo(t.getId());
        assertThat(req.applicationFeeMinor()).isEqualTo(500 + 2500); // 5 Kč + 2,5 % z 1 000 Kč
        assertThat(req.productName()).isEqualTo("Dárkový voucher Kavárna vinarna – 1\u00a0000\u00a0Kč");
        // bez vlastního webu → návrat na hostovanou stránku
        assertThat(req.successUrl()).isEqualTo("http://localhost:8080/v/vinarna/hotovo?session_id={CHECKOUT_SESSION_ID}");
        assertThat(req.cancelUrl()).startsWith("http://localhost:8080/v/vinarna?offer=CZK_1000");

        assertThat(v.getTenantId()).isEqualTo(t.getId());
        assertThat(v.getValidUntil()).isEqualTo(LocalDate.now(clock).plusMonths(6));
        assertThat(jdbc.queryForObject("select stripe_account_id from voucher_order", String.class)).isEqualTo(account);
        assertThat(jdbc.queryForObject("select application_fee_minor from voucher_order", Long.class)).isEqualTo(3000L);
        assertThat(jdbc.queryForObject("select account from stripe_event where type = 'checkout.session.completed'",
                String.class)).isEqualTo(account);
    }

    @Test
    void sessionFromConnectedAccountIsNeverFulfilledViaPlatformOrOtherAccount() throws Exception {
        Tenant a = newTenant("a-cafe", "500");
        Tenant b = newTenant("b-cafe", "500");
        String accountA = connectAndActivate(a.getId());
        String accountB = connectAndActivate(b.getId());

        assertThat(checkoutAt("a-cafe", "CZK_500", "jana@example.com")).isEqualTo(201);
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);

        // Událost s touto session na platformním endpointu (bez účtu) → session na platformě neexistuje.
        assertThat(sendWebhook("checkout.session.completed", "{\"id\":\"" + sid + "\"}")).isEqualTo(200);
        // Událost z cizího připojeného účtu → session na účtu B neexistuje.
        assertThat(sendConnectWebhook(accountB, "checkout.session.completed", "{\"id\":\"" + sid + "\"}"))
                .isEqualTo(200);
        assertThat(count("voucher")).isZero();

        // Podvržená session na účtu B s metadaty objednávky podniku A → nikdy nevystavit voucher.
        UUID orderA = jdbc.queryForObject("select id from voucher_order", UUID.class);
        gateway.putForeignSession(accountB, new CheckoutGateway.SessionSnapshot(
                "cs_test_forged", "payment", "czk", 50000L, "paid", "complete", "pi_forged", orderA.toString()));
        assertThat(sendConnectWebhook(accountB, "checkout.session.completed", "{\"id\":\"cs_test_forged\"}"))
                .isEqualTo(200);
        assertThat(count("voucher")).isZero();

        // Správná událost ze správného účtu → voucher.
        assertThat(sendConnectWebhook(accountA, "checkout.session.completed", "{\"id\":\"" + sid + "\"}"))
                .isEqualTo(200);
        assertThat(count("voucher")).isEqualTo(1);
    }

    @Test
    void refundFromConnectedAccountCancelsVoucherButOtherAccountCannot() throws Exception {
        Tenant a = newTenant("refund-a", "500");
        Tenant b = newTenant("refund-b", "500");
        String accountA = connectAndActivate(a.getId());
        String accountB = connectAndActivate(b.getId());
        Voucher v = buyAt("refund-a", accountA, "CZK_500", "jana@example.com");
        String pi = jdbc.queryForObject("select stripe_payment_intent_id from voucher_order", String.class);

        assertThat(sendConnectWebhook(accountB, "charge.refunded",
                "{\"id\":\"ch_1\",\"payment_intent\":\"" + pi + "\",\"amount_refunded\":50000}")).isEqualTo(200);
        assertThat(vouchers.findById(v.getId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");

        assertThat(sendConnectWebhook(accountA, "charge.refunded",
                "{\"id\":\"ch_1\",\"payment_intent\":\"" + pi + "\",\"amount_refunded\":50000}")).isEqualTo(200);
        assertThat(vouchers.findById(v.getId()).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    }

    @Test
    void connectEndpointRejectsPlatformSignatureAndEventsWithoutAccount() throws Exception {
        String payload = webhookPayload("evt_x", "checkout.session.completed", "{\"id\":\"cs_x\"}", false, "acct_1");
        mvc.perform(post("/api/stripe/connect-webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, WEBHOOK_SECRET, Instant.now()))
                        .content(payload))
                .andExpect(status().isBadRequest());
        String noAccount = webhookPayload("evt_y", "checkout.session.completed", "{\"id\":\"cs_x\"}", false, null);
        mvc.perform(post("/api/stripe/connect-webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(noAccount, CONNECT_WEBHOOK_SECRET, Instant.now()))
                        .content(noAccount))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from stripe_event where event_id = 'evt_y'", String.class))
                .isEqualTo("IGNORED");
    }

    // --- izolace dat ------------------------------------------------------------------------

    @Test
    void staffAndManagersSeeOnlyTheirOwnTenant() throws Exception {
        Tenant a = newTenant("iso-a", "500");
        Tenant b = newTenant("iso-b", "500");
        String accountA = connectAndActivate(a.getId());
        connectAndActivate(b.getId());
        Voucher va = buyAt("iso-a", accountA, "CZK_500", "anna@example.com");

        StaffUser staffA = createStaff(a.getId(), "staff-a", "staff-a-password", StaffRole.STAFF);
        StaffUser staffB = createStaff(b.getId(), "staff-b", "staff-b-password", StaffRole.STAFF);
        StaffUser bossA = createStaff(a.getId(), "boss-a", "boss-a-password1", StaffRole.MANAGER);
        StaffUser bossB = createStaff(b.getId(), "boss-b", "boss-b-password1", StaffRole.MANAGER);

        // Obsluha B: kód podniku A „neexistuje“ (ani přes QR adresu), uplatnit nejde.
        mvc.perform(get("/staff/v/" + va.getCode()).with(as(staffB))).andExpect(redirectedUrl("/staff"));
        mvc.perform(post("/staff/v/" + va.getCode() + "/redeem").with(as(staffB)).with(csrf())
                        .param("acknowledged", "true"))
                .andExpect(redirectedUrl("/staff"));
        assertThat(vouchers.findById(va.getId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'LOOKUP_NOT_FOUND'"
                + " and tenant_id = ?", Integer.class, b.getId())).isEqualTo(1);

        // Obsluha A ho najde.
        mvc.perform(get("/staff/v/" + va.getCode()).with(as(staffA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("500")));

        // Vedoucí B: hledání podle e-mailu, kódu ani detail voucheru A nic nevrátí; zásah nejde.
        mvc.perform(get("/admin/vouchers").with(as(bossB)).param("q", "anna@example.com"))
                .andExpect(content().string(not(containsString(va.getId().toString()))));
        mvc.perform(get("/admin/vouchers").with(as(bossB)).param("q", va.getCode()))
                .andExpect(content().string(not(containsString(va.getId().toString()))));
        mvc.perform(get("/admin/vouchers/" + va.getId()).with(as(bossB))).andExpect(redirectedUrl("/admin/vouchers"));
        mvc.perform(post("/admin/vouchers/" + va.getId() + "/cancel").with(as(bossB)).with(csrf())
                .param("reason", "pokus o zásah"));
        assertThat(vouchers.findById(va.getId()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");

        // Vedoucí A vidí.
        mvc.perform(get("/admin/vouchers").with(as(bossA)).param("q", "anna@example.com"))
                .andExpect(content().string(containsString("anna@example.com")));

        // Účty obsluhy: B nevidí ani nezneplatní účet A.
        mvc.perform(get("/admin/staff").with(as(bossB)))
                .andExpect(content().string(not(containsString("staff-a"))))
                .andExpect(content().string(containsString("staff-b")));
        mvc.perform(post("/admin/staff/" + staffA.getId() + "/disable").with(as(bossB)).with(csrf()));
        assertThat(staffUsers.findById(staffA.getId()).orElseThrow().isEnabled()).isTrue();

        // Export: jen vlastní podnik.
        String csvB = new String(export.csv(b.getId(), YearMonth.now(clock), LocalDate.now(clock)),
                StandardCharsets.UTF_8);
        String csvA = new String(export.csv(a.getId(), YearMonth.now(clock), LocalDate.now(clock)),
                StandardCharsets.UTF_8);
        assertThat(csvB).doesNotContain("PRODANÝ");
        assertThat(csvA).contains("PRODANÝ");

        // Veřejné API: stav objednávky A přes adresu podniku B je neznámý.
        String sid = jdbc.queryForObject("select stripe_checkout_session_id from voucher_order", String.class);
        mvc.perform(get("/api/public/t/iso-b/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        mvc.perform(get("/api/public/t/iso-a/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.status").value("UNKNOWN")); // výchozí podnik S74
    }

    @Test
    void rolesAreSeparated() throws Exception {
        StaffUser boss = createStaff("boss", "boss-password-123", StaffRole.MANAGER);
        mvc.perform(get("/platform").with(as(boss))).andExpect(status().isForbidden());
        mvc.perform(post("/platform/tenants").with(as(boss)).with(csrf()).param("slug", "x"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/staff").with(as(platformAdmin))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/vouchers").with(as(platformAdmin))).andExpect(status().isForbidden());
        mvc.perform(get("/platform").with(as(platformAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("S74 wine&amp;café")));
        // vedoucí nemůže založit správce platformy
        MvcResult r = mvc.perform(post("/admin/staff").with(as(boss)).with(csrf()).param("username", "evil")
                        .param("displayName", "Evil").param("role", "PLATFORM_ADMIN")
                        .param("password", "dostatecne-dlouhe-heslo"))
                .andReturn();
        assertThat(r.getFlashMap().get("error")).isNotNull();
        assertThat(staffUsers.findByUsernameIgnoreCase("evil")).isEmpty();
    }

    @Test
    void platformAdminLoginLandsOnPlatform() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "platform").param("password", "platform-password-1"))
                .andExpect(redirectedUrl("/platform"));
    }

    @Test
    void platformAdminCreatesFirstManagerOfTenant() throws Exception {
        Tenant t = newTenant("novy", "500");
        mvc.perform(post("/platform/tenants/" + t.getId() + "/managers").with(as(platformAdmin)).with(csrf())
                .param("username", "novy-boss").param("displayName", "Nová vedoucí")
                .param("password", "dostatecne-dlouhe-heslo"));
        StaffUser created = staffUsers.findByUsernameIgnoreCase("novy-boss").orElseThrow();
        assertThat(created.getTenantId()).isEqualTo(t.getId());
        assertThat(created.getRole()).isEqualTo(StaffRole.MANAGER);
        mvc.perform(get("/platform/tenants/" + t.getId()).with(as(platformAdmin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("novy-boss")))
                .andExpect(content().string(containsString("Podnik založen")));
    }

    @Test
    void suspendedTenantStopsSellingButExistingVouchersCanBeRedeemed() throws Exception {
        Tenant t = newTenant("pauza", "500");
        String account = connectAndActivate(t.getId());
        Voucher v = buyAt("pauza", account, "CZK_500", "jana@example.com");
        StaffUser staff = createStaff(t.getId(), "pauza-staff", "pauza-password-1", StaffRole.STAFF);

        mvc.perform(post("/platform/tenants/" + t.getId() + "/suspend").with(as(platformAdmin)).with(csrf())
                .param("reason", "neplacení paušálu"));
        assertThat(checkoutAt("pauza", "CZK_500", "jana@example.com")).isEqualTo(409);

        mvc.perform(post("/staff/v/" + v.getCode() + "/redeem").with(as(staff)).with(csrf())
                .param("acknowledged", "true"));
        assertThat(vouchers.findById(v.getId()).orElseThrow().getStatus().name()).isEqualTo("REDEEMED");
    }

    @Test
    void defaultTenantSwitchesToConnectOnlyWhenAccountActive() throws Exception {
        // starý nákup na platformním účtu
        Voucher old = issueVoucher("CZK_500", "stary@example.com");
        String oldPi = jdbc.queryForObject("select stripe_payment_intent_id from voucher_order", String.class);

        mvc.perform(post("/platform/tenants/" + DEFAULT_TENANT + "/payment-mode/connect").with(as(platformAdmin))
                .with(csrf()));
        assertThat(tenants.findById(DEFAULT_TENANT).orElseThrow().getPaymentMode()).isEqualTo(Tenant.PaymentMode.PLATFORM);

        String account = connectAndActivate(DEFAULT_TENANT);
        mvc.perform(post("/platform/tenants/" + DEFAULT_TENANT + "/payment-mode/connect").with(as(platformAdmin))
                .with(csrf()));
        assertThat(tenants.findById(DEFAULT_TENANT).orElseThrow().getPaymentMode()).isEqualTo(Tenant.PaymentMode.CONNECT);

        // nový nákup přes původní API výchozího podniku jde na připojený účet
        assertThat(checkout("CZK_1000", "novy@example.com")).isEqualTo(201);
        assertThat(gateway.lastRequest().stripeAccountId()).isEqualTo(account);

        // refund staré platby z platformního účtu dál funguje
        assertThat(sendWebhook("charge.refunded",
                "{\"id\":\"ch_1\",\"payment_intent\":\"" + oldPi + "\",\"amount_refunded\":50000}")).isEqualTo(200);
        assertThat(vouchers.findById(old.getId()).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    }

    // --- CORS -------------------------------------------------------------------------------

    @Test
    void corsOriginsArePerTenant() throws Exception {
        Tenant t = newTenant("web", "500");
        tenantAdmin.updateSales(platformAdmin.getId(), t.getId(), new TenantAdminService.Sales(
                "https://kavarna-web.test", "https://kavarna-web.test", null, null, null, null, null), "127.0.0.1");

        mvc.perform(options("/api/public/t/web/checkout-sessions")
                        .header("Origin", "https://kavarna-web.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://kavarna-web.test"));
        // web S74 nesmí volat API jiného podniku a naopak
        mvc.perform(options("/api/public/t/web/checkout-sessions")
                        .header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/public/checkout-sessions")
                        .header("Origin", "https://kavarna-web.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        // web podniku → návrat na jeho order-success.html
        String account = connectAndActivate(t.getId());
        assertThat(account).isNotNull();
        assertThat(checkoutAt("web", "CZK_500", "jana@example.com")).isEqualTo(201);
        assertThat(gateway.lastRequest().successUrl())
                .isEqualTo("https://kavarna-web.test/order-success.html?session_id={CHECKOUT_SESSION_ID}");
    }

    @Test
    void unknownTenantIs404() throws Exception {
        mvc.perform(get("/api/public/t/neexistuje/offers"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
        assertThat(checkoutAt("neexistuje", "CZK_500", "jana@example.com")).isEqualTo(404);
        mvc.perform(get("/v/neexistuje")).andExpect(status().isNotFound());
    }
}
