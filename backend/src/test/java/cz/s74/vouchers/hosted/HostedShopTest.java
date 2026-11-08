package cz.s74.vouchers.hosted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.connect.ConnectGateway;
import cz.s74.vouchers.connect.ConnectService;
import cz.s74.vouchers.platform.TenantAdminService;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.support.AbstractIT;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Hostované prodejní stránky podniku (/v/{slug}) – bez JavaScriptu, bez cookies. */
class HostedShopTest extends AbstractIT {

    @Autowired TenantAdminService tenantAdmin;
    @Autowired ConnectService connect;
    @Autowired TenantRepository tenants;

    private Tenant tenant;
    private String account;

    @BeforeEach
    void setUp() {
        StaffUser admin = createStaff("platform", "platform-password-1", StaffRole.PLATFORM_ADMIN);
        tenant = tenantAdmin.create(admin.getId(), new TenantAdminService.NewTenant(
                "kafe", "Kafe Na Rohu", "Kafe Na Rohu s.r.o.", "11223344", "Rohová 1, Brno", "ahoj@kafe.test",
                "400, 800"), "127.0.0.1");
        tenantAdmin.updateSales(admin.getId(), tenant.getId(), new TenantAdminService.Sales(
                null, null, null, null, null, null, "#225588"), "127.0.0.1");
        connect.onboardingLink(tenant.getId(), admin.getId(), "127.0.0.1", "https://x/r", "https://x/s");
        account = tenants.findById(tenant.getId()).orElseThrow().getStripeAccountId();
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("active", 0, false));
        connect.refresh(tenant.getId());
    }

    @Test
    void shopPageShowsOffersBrandingAndSetsNoCookie() throws Exception {
        MvcResult r = mvc.perform(get("/v/kafe"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kafe Na Rohu")))
                .andExpect(content().string(containsString("value=\"CZK_400\"")))
                .andExpect(content().string(containsString("value=\"CZK_800\"")))
                .andExpect(content().string(containsString("Kafe Na Rohu s.r.o., IČO 11223344")))
                .andExpect(content().string(containsString("/v/kafe/brand.css")))
                .andExpect(content().string(containsString("Zkušební provoz")))
                .andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(r.getRequest().getSession(false)).isNull();

        mvc.perform(get("/v/kafe/brand.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("--accent: #225588")));
    }

    @Test
    void purchaseRedirectsToStripeAndResultPageShowsVoucher() throws Exception {
        mvc.perform(post("/v/kafe/checkout")
                        .param("offerId", "CZK_800")
                        .param("email", "jana@example.com")
                        .param("emailConfirm", "Jana@example.com")
                        .param("termsAccepted", "true")
                        .param("termsVersion", "2026-11"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", containsString("https://checkout.stripe.test/c/pay/")));
        assertThat(gateway.lastRequest().stripeAccountId()).isEqualTo(account);
        assertThat(gateway.lastRequest().successUrl()).contains("/v/kafe/hotovo?session_id=");

        String sid = gateway.lastSessionId();
        mvc.perform(get("/v/kafe/hotovo").param("session_id", sid))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ověřujeme platbu")))
                .andExpect(content().string(containsString("http-equiv=\"refresh\"")));

        gateway.markPaid(sid);
        // Webhook nedorazil – stránka výsledku si platbu ověří sama (stejně jako API).
        mvc.perform(get("/v/kafe/hotovo").param("session_id", sid))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Děkujeme, platba proběhla")))
                .andExpect(content().string(containsString("data:image/png;base64,")));
        assertThat(count("voucher")).isEqualTo(1);
    }

    @Test
    void validationErrorsRerenderFormWithoutCallingStripe() throws Exception {
        mvc.perform(post("/v/kafe/checkout")
                        .param("offerId", "CZK_800")
                        .param("email", "jana@example.com")
                        .param("emailConfirm", "jina@example.com")
                        .param("termsAccepted", "true")
                        .param("termsVersion", "2026-11"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("se neshodují")))
                .andExpect(content().string(containsString("value=\"jana@example.com\"")));
        mvc.perform(post("/v/kafe/checkout")
                        .param("offerId", "CZK_999")
                        .param("email", "jana@example.com")
                        .param("emailConfirm", "jana@example.com")
                        .param("termsAccepted", "true")
                        .param("termsVersion", "2026-11"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("není dostupná")));
        mvc.perform(post("/v/kafe/checkout")
                        .param("offerId", "CZK_400")
                        .param("email", "jana@example.com")
                        .param("emailConfirm", "jana@example.com")
                        .param("termsVersion", "2026-11"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("souhlas")));
        assertThat(gateway.requests).isEmpty();
    }

    @Test
    void termsPageIsGeneratedFromTenantData() throws Exception {
        mvc.perform(get("/v/kafe/podminky"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kafe Na Rohu s.r.o., IČO 11223344")))
                .andExpect(content().string(containsString("Rohová 1, Brno")))
                .andExpect(content().string(containsString("12 měsíců")));
        mvc.perform(get("/api/public/t/kafe/offers"))
                .andExpect(content().string(containsString("http://localhost:8080/v/kafe/podminky")));
    }

    @Test
    void notConnectedTenantShowsPausedSales() throws Exception {
        connectGateway.setStatus(account, new ConnectGateway.AccountStatus("restricted", 2, false));
        connect.refresh(tenant.getId());
        mvc.perform(get("/v/kafe"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pozastaven")));
        mvc.perform(post("/v/kafe/checkout")
                        .param("offerId", "CZK_400")
                        .param("email", "jana@example.com")
                        .param("emailConfirm", "jana@example.com")
                        .param("termsAccepted", "true")
                        .param("termsVersion", "2026-11"))
                .andExpect(status().isConflict());
        assertThat(gateway.requests).isEmpty();
    }
}
