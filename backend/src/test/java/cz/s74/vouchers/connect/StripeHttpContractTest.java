package cz.s74.vouchers.connect;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.stripe.StripeCheckoutGateway;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Skutečné stripe-java SDK proti lokálnímu HTTP serveru, který se tváří jako api.stripe.com: ověřuje, co přesně
 * aplikace posílá (cesta, hlavičky Stripe-Account / Idempotency-Key, tělo v1 formuláře i v2 JSON) a že umí
 * přečíst odpovědi. Doplňuje atrapy v integračních testech; se skutečným Stripe sandboxem viz
 * {@code StripeSandboxSmokeTest}.
 */
class StripeHttpContractTest {

    record Captured(String method, String path, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private StripeClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            String path = exchange.getRequestURI().getPath();
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, body));
            String response = responses.entrySet().stream()
                    .filter(e -> path.startsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("{}");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Request-Id", "req_test");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        client = StripeClient.builder()
                .setApiKey("sk_test_contract")
                .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
                .setMaxNetworkRetries(0)
                .build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void createsV2AccountWithMerchantConfigurationAndStripeResponsibilities() throws Exception {
        responses.put("/v2/core/accounts", "{\"id\":\"acct_123\",\"object\":\"v2.core.account\"}");
        StripeConnectGateway gateway = new StripeConnectGateway(client);
        UUID tenantId = UUID.randomUUID();

        String id = gateway.createAccount(
                new ConnectGateway.NewAccount(tenantId, "Kavárna s.r.o.", "info@kavarna.test", "CZ", "CZK"));

        assertThat(id).isEqualTo("acct_123");
        Captured r = requests.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v2/core/accounts");
        assertThat(r.headers().get("idempotency-key")).isEqualTo("connect-account-" + tenantId);
        assertThat(r.headers().get("stripe-version")).isNotBlank();
        JsonNode body = json.readTree(r.body());
        assertThat(body.path("dashboard").asString()).isEqualTo("full");
        assertThat(body.path("display_name").asString()).isEqualTo("Kavárna s.r.o.");
        assertThat(body.path("contact_email").asString()).isEqualTo("info@kavarna.test");
        assertThat(body.path("identity").path("country").asString()).isEqualTo("cz");
        assertThat(body.path("defaults").path("currency").asString()).isEqualTo("czk");
        assertThat(body.path("defaults").path("responsibilities").path("fees_collector").asString()).isEqualTo("stripe");
        assertThat(body.path("defaults").path("responsibilities").path("losses_collector").asString()).isEqualTo("stripe");
        assertThat(body.path("configuration").path("merchant").path("capabilities").path("card_payments")
                .path("requested").asBoolean()).isTrue();
        assertThat(body.path("metadata").path("tenant_id").asString()).isEqualTo(tenantId.toString());
        assertThat(body.path("include").toString()).contains("configuration.merchant", "requirements");
    }

    @Test
    void createsV2AccountLinkForMerchantOnboarding() throws Exception {
        responses.put("/v2/core/account_links",
                "{\"object\":\"v2.core.account_link\",\"account\":\"acct_123\",\"url\":\"https://connect.stripe.com/setup/x\"}");
        String url = new StripeConnectGateway(client)
                .createOnboardingLink("acct_123", "https://app.test/refresh", "https://app.test/return");

        assertThat(url).isEqualTo("https://connect.stripe.com/setup/x");
        JsonNode body = json.readTree(requests.get(0).body());
        assertThat(requests.get(0).path()).isEqualTo("/v2/core/account_links");
        assertThat(body.path("account").asString()).isEqualTo("acct_123");
        assertThat(body.path("use_case").path("type").asString()).isEqualTo("account_onboarding");
        JsonNode onboarding = body.path("use_case").path("account_onboarding");
        assertThat(onboarding.path("configurations").get(0).asString()).isEqualTo("merchant");
        assertThat(onboarding.path("refresh_url").asString()).isEqualTo("https://app.test/refresh");
        assertThat(onboarding.path("return_url").asString()).isEqualTo("https://app.test/return");
    }

    @Test
    void readsCardPaymentsCapabilityAndRequirementsFromV2Account() throws Exception {
        responses.put("/v2/core/accounts/acct_123", "{\"id\":\"acct_123\",\"object\":\"v2.core.account\","
                + "\"closed\":false,"
                + "\"configuration\":{\"merchant\":{\"capabilities\":{\"card_payments\":{\"status\":\"restricted\"}}}},"
                + "\"requirements\":{\"entries\":["
                + "{\"awaiting_action_from\":\"user\",\"description\":\"company tax id\"},"
                + "{\"awaiting_action_from\":\"stripe\",\"description\":\"review\"}]}}");
        ConnectGateway.AccountStatus status = new StripeConnectGateway(client).retrieveStatus("acct_123");

        assertThat(status.cardPayments()).isEqualTo("restricted");
        assertThat(status.requirementsDue()).isEqualTo(1);
        assertThat(status.canAcceptPayments()).isFalse();
        Captured r = requests.get(0);
        assertThat(r.method()).isEqualTo("GET");
        assertThat(URLDecoder.decode(r.path(), StandardCharsets.UTF_8))
                .startsWith("/v2/core/accounts/acct_123")
                .contains("configuration.merchant", "requirements");
    }

    @Test
    void directChargeCheckoutSessionGoesToConnectedAccountWithApplicationFee() throws Exception {
        responses.put("/v1/checkout/sessions", "{\"id\":\"cs_test_1\",\"object\":\"checkout.session\","
                + "\"url\":\"https://checkout.stripe.com/c/pay/cs_test_1\"}");
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(client);
        UUID orderId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        CheckoutGateway.CreatedSession created = gateway.createSession(new CheckoutGateway.SessionRequest(
                orderId, "ABCDEF12", tenantId, "acct_shop", 3000, "CZK_1000", 100000, "CZK", "jana@example.com",
                "Dárkový voucher Kavárna – 1 000 Kč", "Jednorázový voucher", "Voucher Kavárna ABCDEF12",
                "https://app.test/ok?session_id={CHECKOUT_SESSION_ID}", "https://app.test/cancel",
                Instant.now().plusSeconds(3600)));

        assertThat(created.url()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        Captured r = requests.get(0);
        assertThat(r.path()).isEqualTo("/v1/checkout/sessions");
        assertThat(r.headers().get("stripe-account")).isEqualTo("acct_shop");
        assertThat(r.headers().get("idempotency-key")).isEqualTo("checkout-session-" + orderId);
        Map<String, String> f = form(r.body());
        assertThat(f.get("payment_intent_data[application_fee_amount]")).isEqualTo("3000");
        assertThat(f.get("payment_intent_data[metadata][order_id]")).isEqualTo(orderId.toString());
        assertThat(f.get("payment_intent_data[metadata][tenant_id]")).isEqualTo(tenantId.toString());
        assertThat(f.get("metadata[order_id]")).isEqualTo(orderId.toString());
        assertThat(f.get("line_items[0][price_data][unit_amount]")).isEqualTo("100000");
        assertThat(f.get("success_url")).isEqualTo("https://app.test/ok?session_id={CHECKOUT_SESSION_ID}");
        assertThat(f).doesNotContainKey("payment_method_types[0]");
    }

    @Test
    void platformCheckoutHasNoStripeAccountHeaderNorFee() throws Exception {
        responses.put("/v1/checkout/sessions", "{\"id\":\"cs_test_2\",\"object\":\"checkout.session\",\"url\":\"u\"}");
        new StripeCheckoutGateway(client).createSession(new CheckoutGateway.SessionRequest(
                UUID.randomUUID(), "ABCDEF12", UUID.randomUUID(), null, 0, "CZK_500", 50000, "CZK", "jana@example.com",
                "Voucher", "Popis", "Voucher S74", "https://app.test/ok", "https://app.test/cancel",
                Instant.now().plusSeconds(3600)));
        Captured r = requests.get(0);
        assertThat(r.headers()).doesNotContainKey("stripe-account");
        assertThat(form(r.body())).doesNotContainKey("payment_intent_data[application_fee_amount]");
    }

    @Test
    void retrievesSessionAndPaymentIntentFromConnectedAccount() throws Exception {
        responses.put("/v1/checkout/sessions/cs_1", "{\"id\":\"cs_1\",\"object\":\"checkout.session\","
                + "\"mode\":\"payment\",\"currency\":\"czk\",\"amount_total\":50000,\"payment_status\":\"paid\","
                + "\"status\":\"complete\",\"payment_intent\":\"pi_1\",\"metadata\":{\"order_id\":\"o-1\"}}");
        responses.put("/v1/payment_intents/pi_1",
                "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"metadata\":{\"order_id\":\"o-1\"}}");
        StripeCheckoutGateway gateway = new StripeCheckoutGateway(client);

        CheckoutGateway.SessionSnapshot s = gateway.retrieveSession("acct_shop", "cs_1").orElseThrow();
        assertThat(s.paymentStatus()).isEqualTo("paid");
        assertThat(s.orderId()).isEqualTo("o-1");
        assertThat(gateway.orderIdOfPaymentIntent("acct_shop", "pi_1")).contains("o-1");
        assertThat(requests).allSatisfy(r -> assertThat(r.headers().get("stripe-account")).isEqualTo("acct_shop"));
    }
}
