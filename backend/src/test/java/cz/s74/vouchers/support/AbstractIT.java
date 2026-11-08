package cz.s74.vouchers.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.staff.StaffUserRepository;
import cz.s74.vouchers.tenant.Tenants;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
public abstract class AbstractIT extends PostgresSupport {

    public static final String WEBHOOK_SECRET = "whsec_testsecret";
    public static final String CONNECT_WEBHOOK_SECRET = "whsec_connectsecret";
    public static final String ACCOUNT_EVENTS_SECRET = "whsec_accountevents";
    public static final UUID DEFAULT_TENANT = Tenants.DEFAULT_ID;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeCheckoutGateway gateway;
    @Autowired protected FakeConnectGateway connectGateway;
    @Autowired protected RateLimits limits;
    @Autowired protected VoucherRepository vouchers;
    @Autowired protected StaffUserRepository staffUsers;
    @Autowired protected PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanState() {
        jdbc.execute("truncate audit_log, email_delivery, voucher, voucher_order, stripe_event, staff_user"
                + " restart identity cascade");
        jdbc.update("delete from tenant_offer");
        jdbc.update("delete from tenant where id <> ?", DEFAULT_TENANT);
        jdbc.update("update tenant set status = 'ACTIVE', payment_mode = 'PLATFORM', stripe_account_id = null,"
                + " stripe_card_payments = null, stripe_requirements_due = 0, frontend_base_url = null,"
                + " cors_allowed_origins = null, terms_url = null, terms_version = null, validity_months = null,"
                + " show_on_return_minutes = null, brand_color = null, reply_to = null, application_fee_bps = 0,"
                + " application_fee_fixed_minor = 0 where id = ?", DEFAULT_TENANT);
        limits.resetAll();
        gateway.reset();
        connectGateway.reset();
        clock.reset();
    }

    protected int checkout(String offerId, String email) throws Exception {
        return mvc.perform(post("/api/public/checkout-sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost:8000")
                        .content("{\"offerId\":\"" + offerId + "\",\"email\":\"" + email
                                + "\",\"termsAccepted\":true,\"termsVersion\":\"2026-11\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** Vytvoří objednávku, "zaplatí" ji a pošle podepsaný webhook; vrátí vystavený voucher. */
    protected Voucher issueVoucher(String offerId, String email) throws Exception {
        checkout(offerId, email);
        String sessionId = gateway.lastSessionId();
        gateway.markPaid(sessionId);
        sendWebhook("checkout.session.completed", "{\"id\":\"" + sessionId + "\",\"object\":\"checkout.session\"}");
        return vouchers.findAll().stream().findFirst().orElseThrow();
    }

    protected int sendWebhook(String type, String objectJson) throws Exception {
        return sendWebhook("evt_" + UUID.randomUUID(), type, objectJson, false);
    }

    protected int sendWebhook(String eventId, String type, String objectJson, boolean livemode) throws Exception {
        String payload = webhookPayload(eventId, type, objectJson, livemode);
        return mvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, WEBHOOK_SECRET, Instant.now()))
                        .content(payload))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /** Událost připojeného účtu podniku (Connect endpoint) – nese {@code account}. */
    protected int sendConnectWebhook(String account, String type, String objectJson) throws Exception {
        String payload = webhookPayload("evt_" + UUID.randomUUID(), type, objectJson, false, account);
        return mvc.perform(post("/api/stripe/connect-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, CONNECT_WEBHOOK_SECRET, Instant.now()))
                        .content(payload))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    protected static String webhookPayload(String eventId, String type, String objectJson, boolean livemode) {
        return webhookPayload(eventId, type, objectJson, livemode, null);
    }

    protected static String webhookPayload(
            String eventId, String type, String objectJson, boolean livemode, String account) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\","
                + (account == null ? "" : "\"account\":\"" + account + "\",")
                + "\"created\":" + Instant.now().getEpochSecond() + ",\"livemode\":" + livemode
                + ",\"type\":\"" + type + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    /** Hlavička {@code Stripe-Signature}: HMAC-SHA256 nad {@code t.payload}. */
    protected static String signature(String payload, String secret, Instant at) throws Exception {
        long t = at.getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((t + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + t + ",v1=" + sig;
    }

    protected StaffUser createStaff(String username, String password, StaffRole role) {
        return createStaff(role == StaffRole.PLATFORM_ADMIN ? null : DEFAULT_TENANT, username, password, role);
    }

    protected StaffUser createStaff(UUID tenantId, String username, String password, StaffRole role) {
        return staffUsers.saveAndFlush(new StaffUser(
                UUID.randomUUID(), tenantId, username, "Jméno " + username, passwordEncoder.encode(password), role,
                Instant.now()));
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
