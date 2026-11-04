package cz.s74.vouchers.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.support.AbstractIT;
import cz.s74.vouchers.voucher.Voucher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

class StaffFlowTest extends AbstractIT {

    private StaffUser barista;
    private Voucher voucher;

    @BeforeEach
    void setUp() throws Exception {
        barista = createStaff("barista", "correct-horse-battery", StaffRole.STAFF);
        voucher = issueVoucher("CZK_1000", "jana@example.com");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf()).param("username", username).param("password", password));
        return session;
    }

    private MvcResult loginResult(MockHttpSession session, String username, String password) throws Exception {
        return mvc.perform(post("/login").session(session).with(csrf()).param("username", username)
                        .param("password", password))
                .andReturn();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor asBarista() {
        return user(new StaffPrincipal(barista));
    }

    @Test
    void unauthenticatedQrVisitIsRedirectedToLoginAndBackToVoucherAfterLogin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String path = "/staff/v/" + voucher.getCode();
        mvc.perform(get(path).session(session)).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));

        MvcResult result = loginResult(session, "barista", "correct-horse-battery");
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("http://localhost" + path);

        mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 000 Kč")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Platný")));
    }

    @Test
    void loginWithBadPasswordIsGenericError() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult bad = loginResult(session, "barista", "wrong");
        MvcResult unknown = loginResult(new MockHttpSession(), "nobody", "wrong");
        assertThat(bad.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(unknown.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(count("audit_log")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        barista.disable(clock.instant());
        staffUsers.saveAndFlush(barista);
        MvcResult result = loginResult(new MockHttpSession(), "barista", "correct-horse-battery");
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void fiveFailedLoginsLockAccountEvenForCorrectPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(loginResult(new MockHttpSession(), "barista", "nope" + i).getResponse().getRedirectedUrl())
                    .isEqualTo("/login?error");
        }
        MvcResult locked = loginResult(new MockHttpSession(), "barista", "correct-horse-battery");
        assertThat(locked.getResponse().getRedirectedUrl()).isEqualTo("/login?locked");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'LOGIN_LOCKED'", Integer.class))
                .isEqualTo(1);
        mvc.perform(get("/login").param("locked", ""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Příliš mnoho pokusů, zkuste to za 15 minut.")));

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(16)));
        MvcResult ok = loginResult(new MockHttpSession(), "barista", "correct-horse-battery");
        assertThat(ok.getResponse().getRedirectedUrl()).isEqualTo("/staff");
    }

    @Test
    void staffCannotOpenAdminAreaButCsrfIsEnforced() throws Exception {
        mvc.perform(get("/admin/vouchers").with(asBarista())).andExpect(status().isForbidden());
        mvc.perform(post("/staff/lookup").with(asBarista()).param("code", voucher.getCode()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(asBarista()).param("acknowledged", "true"))
                .andExpect(status().isForbidden());
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void anonymousCannotReachStaffOrOtherEndpoints() throws Exception {
        mvc.perform(get("/staff")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/staff/lookup").with(csrf()).param("code", "x"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        int envStatus = mvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();
        assertThat(envStatus).isNotEqualTo(200);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void securityHeadersArePresentAndPagesAreNotCached() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/staff/v/" + voucher.getCode()).with(asBarista()))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void lookupToleratesSpacesAndCaseAndViewDoesNotRedeem() throws Exception {
        String c = voucher.getCode();
        String typed = (c.substring(0, 4) + " " + c.substring(4, 8) + "-" + c.substring(8)).toLowerCase();
        mvc.perform(post("/staff/lookup").with(asBarista()).with(csrf()).param("code", typed))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/staff/v/" + c));
        mvc.perform(get("/staff/v/" + c).with(asBarista())).andExpect(status().isOk());
        mvc.perform(get("/staff/v/" + c).with(asBarista())).andExpect(status().isOk());
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'VOUCHER_VIEWED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void wrongShapeIsNotCountedButUnknownCodesAreLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/staff/lookup").with(asBarista()).with(csrf()).param("code", "ABC"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/staff"));
        }
        // 10 neexistujících kódů vyčerpá limit účtu
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/staff/lookup").with(asBarista()).with(csrf()).param("code", "ZZZZ-ZZZZ-ZZZ" + i));
        }
        // další pokus, i s existujícím kódem, je zablokován
        MvcResult blocked = mvc.perform(post("/staff/lookup").with(asBarista()).with(csrf()).param("code", voucher.getCode()))
                .andReturn();
        assertThat(blocked.getResponse().getRedirectedUrl()).isEqualTo("/staff");
        assertThat(blocked.getFlashMap().get("error").toString()).contains("Příliš mnoho");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'RATE_LIMITED'", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void unknownCodeShowsFriendlyError() throws Exception {
        MvcResult r = mvc.perform(post("/staff/lookup").with(asBarista()).with(csrf()).param("code", "ZZZZ-ZZZZ-ZZZZ"))
                .andReturn();
        assertThat(r.getFlashMap().get("error")).isEqualTo("Kód nenalezen. Zkontrolujte překlep a zkuste znovu.");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'LOOKUP_NOT_FOUND'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void redeemRequiresAcknowledgement() throws Exception {
        MvcResult r = mvc.perform(post("/staff/v/" + voucher.getCode() + "/redeem").with(asBarista()).with(csrf()))
                .andReturn();
        assertThat(r.getFlashMap().get("error").toString()).contains("upozornili");
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("ACTIVE");
    }

    @Test
    void redeemSucceedsOnceAndShowsConfirmationEvenAfterRefresh() throws Exception {
        String path = "/staff/v/" + voucher.getCode();
        mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true")
                        .param("note", "účtenka 1234"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(path));

        var page = mvc.perform(get(path).with(asBarista())).andExpect(status().isOk()).andReturn();
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("UPLATNĚNO").contains("odečtěte").contains("účtenka 1234");
        assertThat(html).doesNotContain("name=\"acknowledged\""); // žádný formulář k opětovnému uplatnění

        Voucher v = vouchers.findAll().get(0);
        assertThat(v.getStatus().name()).isEqualTo("REDEEMED");
        assertThat(v.getRedeemedBy()).isEqualTo(barista.getId());
        assertThat(v.getRedeemedAt()).isNotNull();

        // dvojí odeslání: druhý požadavek nic nezmění, uživatel dostane srozumitelnou odpověď
        MvcResult second = mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true"))
                .andReturn();
        assertThat(second.getFlashMap().get("error").toString()).contains("už není platný");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'VOUCHER_REDEEMED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'REDEMPTION_REJECTED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredVoucherCannotBeRedeemed() throws Exception {
        clock.setInstant(clock.instant().plus(Duration.ofDays(400)));
        String path = "/staff/v/" + voucher.getCode();
        String html = mvc.perform(get(path).with(asBarista())).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Vypršel").contains("Voucher nelze uplatnit").doesNotContain("name=\"acknowledged\"");

        mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true"));
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'REDEMPTION_REJECTED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void lastValidDayIsStillRedeemable() throws Exception {
        clock.setInstant(voucher.getValidUntil().atTime(23, 30).atZone(java.time.ZoneId.of("Europe/Prague")).toInstant());
        String path = "/staff/v/" + voucher.getCode();
        mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true"));
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("REDEEMED");
    }

    @Test
    void cancelledVoucherCannotBeRedeemed() throws Exception {
        jdbc.update("update voucher set status = 'CANCELLED', cancel_reason = 'REFUND'");
        String path = "/staff/v/" + voucher.getCode();
        String html = mvc.perform(get(path).with(asBarista())).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Zrušen").contains("Vrácení platby").contains("Voucher nelze uplatnit");
        mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true"));
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("CANCELLED");
    }

    @Test
    void concurrentRedemptionsLetExactlyOneSucceed() throws Exception {
        StaffUser other = createStaff("barista2", "another-long-password", StaffRole.STAFF);
        String path = "/staff/v/" + voucher.getCode();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final StaffUser who = i % 2 == 0 ? barista : other;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                MvcResult r = mvc.perform(post(path + "/redeem").with(user(new StaffPrincipal(who))).with(csrf())
                                .param("acknowledged", "true"))
                        .andReturn();
                return r.getFlashMap().get("error") == null; // bez chyby = uplatnil
            }));
        }
        ready.await();
        go.countDown();
        int successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
        assertThat(vouchers.findAll().get(0).getStatus().name()).isEqualTo("REDEEMED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'VOUCHER_REDEEMED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'REDEMPTION_REJECTED'", Integer.class))
                .isEqualTo(threads - 1);
    }

    @Test
    void auditNeverContainsFullVoucherCode() throws Exception {
        String path = "/staff/v/" + voucher.getCode();
        mvc.perform(get(path).with(asBarista()));
        mvc.perform(post(path + "/redeem").with(asBarista()).with(csrf()).param("acknowledged", "true"));
        Integer leaks = jdbc.queryForObject(
                "select count(*) from audit_log where cast(details as varchar) like ?", Integer.class,
                "%" + voucher.getCode() + "%");
        assertThat(leaks).isZero();
    }

    @Test
    void bootstrapLikeManagerCanLogInAndLogOut() throws Exception {
        createStaff("boss", "boss-password-123", StaffRole.MANAGER);
        MockHttpSession session = new MockHttpSession();
        assertThat(loginResult(session, "boss", "boss-password-123").getResponse().getRedirectedUrl()).isEqualTo("/staff");
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?logout"));
        mvc.perform(get("/staff").session(session)).andExpect(status().isFound());
    }
}
