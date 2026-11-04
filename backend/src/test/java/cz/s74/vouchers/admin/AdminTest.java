package cz.s74.vouchers.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.email.EmailOutbox;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.support.AbstractIT;
import cz.s74.vouchers.voucher.Voucher;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class AdminTest extends AbstractIT {

    @Autowired EmailOutbox outbox;

    private StaffUser boss;
    private StaffUser barista;
    private Voucher voucher;

    @BeforeEach
    void setUp() throws Exception {
        boss = createStaff("boss", "boss-password-123", StaffRole.MANAGER);
        barista = createStaff("barista", "barista-password-1", StaffRole.STAFF);
        voucher = issueVoucher("CZK_1000", "jana@example.com");
    }

    private RequestPostProcessor asBoss() {
        return user(new StaffPrincipal(boss));
    }

    private String id() {
        return voucher.getId().toString();
    }

    private String voucherStatus() {
        return jdbc.queryForObject("select status from voucher", String.class);
    }

    private int audit(String action) {
        return jdbc.queryForObject("select count(*) from audit_log where action = ?", Integer.class, action);
    }

    @Test
    void staffRoleHasNoAccessToAnyAdminPage() throws Exception {
        RequestPostProcessor asStaff = user(new StaffPrincipal(barista));
        for (String path : new String[] {"/admin", "/admin/vouchers", "/admin/vouchers/" + id(), "/admin/attention",
            "/admin/staff", "/admin/export"}) {
            mvc.perform(get(path).with(asStaff)).andExpect(status().isForbidden());
        }
        for (String action : new String[] {"cancel", "reactivate", "revert", "resend", "extend"}) {
            mvc.perform(post("/admin/vouchers/" + id() + "/" + action).with(asStaff).with(csrf()).param("reason", "test reason"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/staff").with(asStaff).with(csrf()).param("username", "x")).andExpect(status().isForbidden());
        assertThat(voucherStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void managerSearchFindsByCodeEmailAndOrderRef() throws Exception {
        String ref = voucher.getOrderId().toString().substring(0, 8);
        for (String q : new String[] {voucher.getCode().toLowerCase(), "JANA@Example.com", ref}) {
            mvc.perform(get("/admin/vouchers").with(asBoss()).param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("jana@example.com")))
                    .andExpect(content().string(containsString("/admin/vouchers/" + id())));
        }
        mvc.perform(get("/admin/vouchers").with(asBoss()).param("q", "nobody@example.com"))
                .andExpect(content().string(containsString("Nic nenalezeno")));
    }

    @Test
    void detailShowsOrderStripeLinkDeliveryAndHistory() throws Exception {
        outbox.processDue();
        String html = mvc.perform(get("/admin/vouchers/" + id()).with(asBoss())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("jana@example.com").contains("https://dashboard.stripe.com/payments/pi_fake_")
                .contains("Platba potvrzena").contains("Voucher vystaven").contains("ISSUED");
    }

    @Test
    void everyInterventionRequiresReason() throws Exception {
        for (String action : new String[] {"cancel", "reactivate", "revert", "resend"}) {
            MvcResult r = mvc.perform(post("/admin/vouchers/" + id() + "/" + action).with(asBoss()).with(csrf())
                            .param("reason", "  "))
                    .andReturn();
            assertThat(r.getFlashMap().get("error").toString()).contains("důvod");
        }
        MvcResult ext = mvc.perform(post("/admin/vouchers/" + id() + "/extend").with(asBoss()).with(csrf())
                        .param("reason", "").param("validUntil", LocalDate.now().plusYears(3).toString()))
                .andReturn();
        assertThat(ext.getFlashMap().get("error").toString()).contains("důvod");
        assertThat(voucherStatus()).isEqualTo("ACTIVE");
        assertThat(count("email_delivery")).isEqualTo(1);
    }

    @Test
    void cancelReactivateAndRevertAreAuditedWithReason() throws Exception {
        mvc.perform(post("/admin/vouchers/" + id() + "/cancel").with(asBoss()).with(csrf()).param("reason", "zákazník nechce"));
        assertThat(voucherStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select cancel_reason from voucher", String.class)).isEqualTo("zákazník nechce");
        assertThat(audit("VOUCHER_CANCELLED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select cast(details as varchar) from audit_log where action = 'VOUCHER_CANCELLED'", String.class))
                .contains("zákazník nechce");

        // zrušený voucher obsluha neuplatní
        mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(user(new StaffPrincipal(barista))).with(csrf())
                .param("acknowledged", "true"));
        assertThat(voucherStatus()).isEqualTo("CANCELLED");

        mvc.perform(post("/admin/vouchers/" + id() + "/reactivate").with(asBoss()).with(csrf()).param("reason", "omyl"));
        assertThat(voucherStatus()).isEqualTo("ACTIVE");
        assertThat(audit("VOUCHER_REACTIVATED")).isEqualTo(1);

        mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(user(new StaffPrincipal(barista))).with(csrf())
                .param("acknowledged", "true"));
        assertThat(voucherStatus()).isEqualTo("REDEEMED");

        mvc.perform(post("/admin/vouchers/" + id() + "/revert").with(asBoss()).with(csrf()).param("reason", "chyba v pokladně"));
        assertThat(voucherStatus()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select redeemed_by from voucher", String.class)).isNull();
        assertThat(audit("REDEMPTION_REVERTED")).isEqualTo(1);
    }

    @Test
    void cancelOnlyWorksOnActiveVoucher() throws Exception {
        jdbc.update("update voucher set status = 'REDEEMED'");
        MvcResult r = mvc.perform(post("/admin/vouchers/" + id() + "/cancel").with(asBoss()).with(csrf()).param("reason", "test"))
                .andReturn();
        assertThat(r.getFlashMap().get("error").toString()).contains("platný");
        assertThat(voucherStatus()).isEqualTo("REDEEMED");
    }

    @Test
    void extendValidityRevivesExpiredVoucherAndRejectsPastDates() throws Exception {
        clock.setInstant(clock.instant().plus(java.time.Duration.ofDays(400)));
        LocalDate newDate = LocalDate.now(clock).plusMonths(3);
        mvc.perform(post("/admin/vouchers/" + id() + "/extend").with(asBoss()).with(csrf())
                .param("reason", "omluva zákazníkovi").param("validUntil", newDate.toString()));
        assertThat(jdbc.queryForObject("select valid_until from voucher", java.sql.Date.class).toLocalDate()).isEqualTo(newDate);
        assertThat(audit("VALIDITY_EXTENDED")).isEqualTo(1);

        MvcResult past = mvc.perform(post("/admin/vouchers/" + id() + "/extend").with(asBoss()).with(csrf())
                        .param("reason", "test").param("validUntil", "2020-01-01"))
                .andReturn();
        assertThat(past.getFlashMap().get("error").toString()).contains("minulosti");
        mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(user(new StaffPrincipal(barista))).with(csrf())
                .param("acknowledged", "true"));
        assertThat(voucherStatus()).isEqualTo("REDEEMED");
    }

    @Test
    void resendToCorrectedAddressUpdatesOrderQueuesMailAndAuditsChange() throws Exception {
        outbox.processDue();
        mvc.perform(post("/admin/vouchers/" + id() + "/resend").with(asBoss()).with(csrf())
                .param("reason", "překlep v adrese").param("email", "Jana@Example.cz"));

        assertThat(jdbc.queryForObject("select customer_email from voucher_order", String.class)).isEqualTo("Jana@example.cz");
        assertThat(count("email_delivery")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select recipient from email_delivery where kind = 'RESEND'", String.class))
                .isEqualTo("Jana@example.cz");
        assertThat(jdbc.queryForObject("select requested_by from email_delivery where kind = 'RESEND'", java.util.UUID.class))
                .isEqualTo(boss.getId());
        String details = jdbc.queryForObject(
                "select cast(details as varchar) from audit_log where action = 'EMAIL_RESEND_REQUESTED'", String.class);
        assertThat(details).contains("jana@example.com").contains("Jana@example.cz").contains("překlep v adrese");

        MvcResult bad = mvc.perform(post("/admin/vouchers/" + id() + "/resend").with(asBoss()).with(csrf())
                        .param("reason", "test").param("email", "not-an-email"))
                .andReturn();
        assertThat(bad.getFlashMap().get("error").toString()).contains("platnou");
        assertThat(count("email_delivery")).isEqualTo(2);
    }

    @Test
    void attentionListShowsFlaggedOrdersAndCanBeResolvedWithNote() throws Exception {
        jdbc.update("update voucher_order set attention_required = true, attention_reason = 'Částečný refund'");
        mvc.perform(get("/admin/attention").with(asBoss()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Částečný refund")));

        mvc.perform(post("/admin/attention/" + voucher.getOrderId() + "/resolve").with(asBoss()).with(csrf()).param("note", ""));
        assertThat(jdbc.queryForObject("select attention_required from voucher_order", Boolean.class)).isTrue();

        mvc.perform(post("/admin/attention/" + voucher.getOrderId() + "/resolve").with(asBoss()).with(csrf())
                .param("note", "vyřešeno s Stripe"));
        assertThat(jdbc.queryForObject("select attention_required from voucher_order", Boolean.class)).isFalse();
        assertThat(audit("ATTENTION_RESOLVED")).isEqualTo(1);
    }

    @Test
    void attentionPageListsSecurityEvents() throws Exception {
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "barista").param("password", "bad" + i));
        }
        mvc.perform(post("/login").with(csrf()).param("username", "barista").param("password", "bad"));
        mvc.perform(get("/admin/attention").with(asBoss()))
                .andExpect(content().string(containsString("Přihlášení zablokováno")));
    }

    @Test
    void staffAccountManagementRules() throws Exception {
        // krátké heslo
        MvcResult shortPw = mvc.perform(post("/admin/staff").with(asBoss()).with(csrf()).param("username", "novy")
                        .param("displayName", "Nový").param("role", "STAFF").param("password", "krátké"))
                .andReturn();
        assertThat(shortPw.getFlashMap().get("error").toString()).contains("alespoň 12");
        assertThat(staffUsers.findByUsernameIgnoreCase("novy")).isEmpty();

        mvc.perform(post("/admin/staff").with(asBoss()).with(csrf()).param("username", "novy").param("displayName", "Nový")
                .param("role", "STAFF").param("password", "dostatecne-dlouhe-heslo"));
        StaffUser created = staffUsers.findByUsernameIgnoreCase("novy").orElseThrow();
        assertThat(created.getRole()).isEqualTo(StaffRole.STAFF);
        assertThat(created.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(audit("STAFF_CREATED")).isEqualTo(1);

        // duplicitní jméno
        MvcResult dup = mvc.perform(post("/admin/staff").with(asBoss()).with(csrf()).param("username", "NOVY")
                        .param("displayName", "X").param("role", "STAFF").param("password", "dostatecne-dlouhe-heslo"))
                .andReturn();
        assertThat(dup.getFlashMap().get("error").toString()).contains("už existuje");

        // nelze zneplatnit sám sebe ani posledního vedoucího
        MvcResult self = mvc.perform(post("/admin/staff/" + boss.getId() + "/disable").with(asBoss()).with(csrf())).andReturn();
        assertThat(self.getFlashMap().get("error").toString()).contains("vlastní účet");
        assertThat(staffUsers.findById(boss.getId()).orElseThrow().isEnabled()).isTrue();

        StaffUser boss2 = createStaff("boss2", "boss2-password-12", StaffRole.MANAGER);
        mvc.perform(post("/admin/staff/" + boss2.getId() + "/disable").with(asBoss()).with(csrf()));
        assertThat(staffUsers.findById(boss2.getId()).orElseThrow().isEnabled()).isFalse();
        // boss2 už je zneplatněný → boss je zase jediný aktivní vedoucí; z pohledu boss2 by zneplatnění bossa selhalo
        StaffUser boss3 = createStaff("boss3", "boss3-password-12", StaffRole.MANAGER);
        mvc.perform(post("/admin/staff/" + boss.getId() + "/disable").with(user(new StaffPrincipal(boss3))).with(csrf()));
        assertThat(staffUsers.findById(boss.getId()).orElseThrow().isEnabled()).isFalse(); // boss3 ještě aktivní
        MvcResult last = mvc.perform(post("/admin/staff/" + boss3.getId() + "/disable").with(user(new StaffPrincipal(boss2))).with(csrf()))
                .andReturn();
        assertThat(last.getFlashMap().get("error").toString()).contains("posledního");

        mvc.perform(post("/admin/staff/" + boss2.getId() + "/enable").with(asBoss()).with(csrf()));
        assertThat(staffUsers.findById(boss2.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(audit("STAFF_DISABLED")).isEqualTo(2);
        assertThat(audit("STAFF_ENABLED")).isEqualTo(1);
    }

    @Test
    void disablingAccountEndsItsSessionsImmediately() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult login = mvc.perform(post("/login").session(session).with(csrf()).param("username", "barista")
                        .param("password", "barista-password-1"))
                .andReturn();
        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/staff");
        mvc.perform(get("/staff").session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/staff/" + barista.getId() + "/disable").with(asBoss()).with(csrf()));

        MvcResult after = mvc.perform(get("/staff").session(session)).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(302);
        mvc.perform(get("/staff/v/" + voucher.getCode()).session(session)).andExpect(status().is3xxRedirection());
        MvcResult relogin = mvc.perform(post("/login").with(csrf()).param("username", "barista")
                        .param("password", "barista-password-1"))
                .andReturn();
        assertThat(relogin.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void passwordResetChangesLoginCredentials() throws Exception {
        mvc.perform(post("/admin/staff/" + barista.getId() + "/password").with(asBoss()).with(csrf())
                .param("password", "zcela-nove-heslo-99"));
        assertThat(mvc.perform(post("/login").with(csrf()).param("username", "barista").param("password", "barista-password-1"))
                        .andReturn().getResponse().getRedirectedUrl())
                .isEqualTo("/login?error");
        assertThat(mvc.perform(post("/login").with(csrf()).param("username", "barista").param("password", "zcela-nove-heslo-99"))
                        .andReturn().getResponse().getRedirectedUrl())
                .isEqualTo("/staff");
        assertThat(audit("STAFF_PASSWORD_RESET")).isEqualTo(1);
    }

    @Test
    void exportContainsSoldAndRedeemedVouchersForMonthAsBomCsv() throws Exception {
        mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(user(new StaffPrincipal(barista))).with(csrf())
                .param("acknowledged", "true").param("note", "=HYPERLINK(\"x\")"));
        String month = java.time.YearMonth.now().toString();
        MvcResult r = mvc.perform(get("/admin/export").with(asBoss()).param("month", month)).andExpect(status().isOk()).andReturn();
        byte[] body = r.getResponse().getContentAsByteArray();
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);
        String csv = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv).contains("typ;datum;číslo objednávky").contains("PRODANÝ;").contains("UPLATNĚNÝ;")
                .contains("1000").contains("Jméno barista").contains("'=HYPERLINK");
        assertThat(csv).doesNotContain(voucher.getCode());
        assertThat(audit("VOUCHER_REDEEMED")).isEqualTo(1);

        mvc.perform(get("/admin/export").with(asBoss())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Stáhnout CSV")));
    }
}
