package cz.s74.vouchers.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CheckoutApiTest extends AbstractIT {

    private static String body(String offer, String email, String accepted, String version) {
        return "{\"offerId\":" + offer + ",\"email\":" + email + ",\"termsAccepted\":" + accepted
                + ",\"termsVersion\":" + version + "}";
    }

    private org.springframework.test.web.servlet.ResultActions submit(String json) throws Exception {
        return mvc.perform(post("/api/public/checkout-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    void offersAreListedFromConfiguration() throws Exception {
        mvc.perform(get("/api/public/offers"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.currency").value("CZK"))
                .andExpect(jsonPath("$.validityMonths").value(12))
                .andExpect(jsonPath("$.termsVersion").value("2026-11"))
                .andExpect(jsonPath("$.termsUrl").value("http://localhost:8000/obchodni-podminky-vouchery.html"))
                .andExpect(jsonPath("$.offers.length()").value(3))
                .andExpect(jsonPath("$.offers[1].id").value("CZK_1000"))
                .andExpect(jsonPath("$.offers[1].amountCzk").value(1000))
                .andExpect(jsonPath("$.offers[1].label").value("Voucher v hodnotě 1 000 Kč"));
    }

    @Test
    void validRequestCreatesPendingOrderAndReturnsCheckoutUrl() throws Exception {
        submit(body("\"CZK_1000\"", "\"Jana@Example.COM\"", "true", "\"2026-11\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkoutUrl").value(containsString("https://checkout.stripe.test/")));

        assertThat(count("voucher_order")).isEqualTo(1);
        var row = jdbc.queryForMap(
                "select status, amount_minor, customer_email, stripe_checkout_session_id, terms_version"
                        + " from voucher_order");
        assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(row.get("amount_minor")).isEqualTo(100000L);
        assertThat(row.get("customer_email")).isEqualTo("Jana@example.com");
        assertThat(row.get("stripe_checkout_session_id")).isEqualTo(gateway.lastSessionId());
        assertThat(row.get("terms_version")).isEqualTo("2026-11");
        assertThat(count("audit_log")).isEqualTo(1);
    }

    @Test
    void sessionRequestUsesPriceFromCatalogueAndSafeParameters() throws Exception {
        submit(body("\"CZK_2000\"", "\"jana@example.com\"", "true", "\"2026-11\"")).andExpect(status().isCreated());

        var req = gateway.lastRequest();
        assertThat(req.amountMinor()).isEqualTo(200000L);
        assertThat(req.currency()).isEqualTo("CZK");
        assertThat(req.productName()).isEqualTo("Dárkový voucher S74 wine&café – 2 000 Kč");
        assertThat(req.successUrl())
                .isEqualTo("http://localhost:8000/order-success.html?session_id={CHECKOUT_SESSION_ID}");
        assertThat(req.cancelUrl()).isEqualTo("http://localhost:8000/checkout.html?offer=CZK_2000&cancelled=1");
        assertThat(req.expiresAt()).isAfter(java.time.Instant.now().plusSeconds(30 * 60));
    }

    @Test
    void priceInRequestIsIgnored() throws Exception {
        submit("{\"offerId\":\"CZK_500\",\"amountCzk\":1,\"amount\":1,\"email\":\"a@example.com\","
                        + "\"termsAccepted\":true,\"termsVersion\":\"2026-11\"}")
                .andExpect(status().isCreated());
        assertThat(gateway.lastRequest().amountMinor()).isEqualTo(50000L);
    }

    @Test
    void invalidOfferIs400() throws Exception {
        submit(body("\"CZK_999999\"", "\"a@example.com\"", "true", "\"2026-11\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_OFFER"));
        submit(body("null", "\"a@example.com\"", "true", "\"2026-11\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_OFFER"));
        assertThat(count("voucher_order")).isZero();
    }

    @Test
    void invalidEmailIs400() throws Exception {
        for (String email : new String[] {"\"\"", "\"not-an-email\"", "\"a@b\"", "\"a b@example.com\"", "null",
            "\"" + "x".repeat(250) + "@example.com\""}) {
            submit(body("\"CZK_500\"", email, "true", "\"2026-11\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_EMAIL"));
        }
        assertThat(count("voucher_order")).isZero();
    }

    @Test
    void notAcceptedTermsIs400() throws Exception {
        submit(body("\"CZK_500\"", "\"a@example.com\"", "false", "\"2026-11\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TERMS_NOT_ACCEPTED"));
        submit("{\"offerId\":\"CZK_500\",\"email\":\"a@example.com\",\"termsVersion\":\"2026-11\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TERMS_NOT_ACCEPTED"));
    }

    @Test
    void outdatedTermsVersionIs409() throws Exception {
        submit(body("\"CZK_500\"", "\"a@example.com\"", "true", "\"2020-01\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TERMS_OUTDATED"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        submit("{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void stripeOutageIs502AndOrderStaysPending() throws Exception {
        gateway.failCreate = true;
        submit(body("\"CZK_500\"", "\"a@example.com\"", "true", "\"2026-11\""))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("select status from voucher_order", String.class)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void eleventhCheckoutFromSameIpIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            submit(body("\"CZK_500\"", "\"a@example.com\"", "true", "\"2026-11\"")).andExpect(status().isCreated());
        }
        submit(body("\"CZK_500\"", "\"a@example.com\"", "true", "\"2026-11\""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    void corsAllowsConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/public/checkout-sessions")
                        .header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8000"));

        mvc.perform(options("/api/public/checkout-sessions")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/public/offers").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/public/offers").header("Origin", "http://localhost:8000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8000"));
    }
}
