package cz.s74.vouchers.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cz.s74.vouchers.support.AbstractIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WebhookTest extends AbstractIT {

    private static String sessionObj(String id) {
        return "{\"id\":\"" + id + "\",\"object\":\"checkout.session\"}";
    }

    private String orderStatus() {
        return jdbc.queryForObject("select status from voucher_order", String.class);
    }

    @Test
    void invalidSignatureIs400AndNothingHappens() throws Exception {
        checkout("CZK_1000", "a@example.com");
        gateway.markPaid(gateway.lastSessionId());
        String payload = webhookPayload("evt_1", "checkout.session.completed", sessionObj(gateway.lastSessionId()), false);

        mvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, "whsec_wrong", Instant.now()))
                        .content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "garbage")
                        .content(payload))
                .andExpect(status().isBadRequest());
        // tělo změněné po podpisu
        mvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, WEBHOOK_SECRET, Instant.now()))
                        .content(payload.replace("a@example.com", "b@example.com").replace("evt_1", "evt_2")))
                .andExpect(status().isBadRequest());

        assertThat(count("voucher")).isZero();
        assertThat(count("stripe_event")).isZero();
    }

    @Test
    void oldTimestampIsRejected() throws Exception {
        String payload = webhookPayload("evt_old", "checkout.session.completed", sessionObj("cs_x"), false);
        mvc.perform(post("/api/stripe/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", signature(payload, WEBHOOK_SECRET, Instant.now().minusSeconds(3600)))
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validSignatureIssuesExactlyOneVoucherAndQueuesEmail() throws Exception {
        var voucher = issueVoucher("CZK_1000", "jana@example.com");

        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(voucher.getAmountMinor()).isEqualTo(100000L);
        assertThat(voucher.getCode()).matches("[0-9A-HJKMNP-TV-Z]{12}");
        assertThat(voucher.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(voucher.getValidUntil()).isEqualTo(java.time.LocalDate.now(clock).plusMonths(12));
        assertThat(count("email_delivery")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from email_delivery", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select stripe_payment_intent_id from voucher_order", String.class))
                .isEqualTo(gateway.paymentIntentOf(gateway.lastSessionId()));
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("PROCESSED");
    }

    @Test
    void duplicateEventDoesNotCreateSecondVoucher() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        assertThat(sendWebhook("evt_dup", "checkout.session.completed", sessionObj(sid), false)).isEqualTo(200);
        assertThat(sendWebhook("evt_dup", "checkout.session.completed", sessionObj(sid), false)).isEqualTo(200);
        // jiná událost téhož typu pro stejnou session (např. resend) – idempotentní díky stavu objednávky
        assertThat(sendWebhook("evt_other", "checkout.session.completed", sessionObj(sid), false)).isEqualTo(200);
        assertThat(sendWebhook("evt_async", "checkout.session.async_payment_succeeded", sessionObj(sid), false))
                .isEqualTo(200);

        assertThat(count("voucher")).isEqualTo(1);
        assertThat(count("email_delivery")).isEqualTo(1);
        assertThat(count("stripe_event")).isEqualTo(3);
    }

    @Test
    void completedButUnpaidWaitsThenAsyncSuccessIssuesVoucher() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markCompleteUnpaid(sid);
        assertThat(sendWebhook("checkout.session.completed", sessionObj(sid))).isEqualTo(200);
        assertThat(orderStatus()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(count("voucher")).isZero();

        gateway.markPaid(sid);
        assertThat(sendWebhook("checkout.session.async_payment_succeeded", sessionObj(sid))).isEqualTo(200);
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(count("voucher")).isEqualTo(1);
    }

    @Test
    void asyncPaymentFailedMarksOrderFailed() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markCompleteUnpaid(sid);
        sendWebhook("checkout.session.completed", sessionObj(sid));
        assertThat(sendWebhook("checkout.session.async_payment_failed", sessionObj(sid))).isEqualTo(200);
        assertThat(orderStatus()).isEqualTo("FAILED");
        assertThat(count("voucher")).isZero();
    }

    @Test
    void expiredSessionMarksPendingOrderExpired() throws Exception {
        checkout("CZK_500", "a@example.com");
        assertThat(sendWebhook("checkout.session.expired", sessionObj(gateway.lastSessionId()))).isEqualTo(200);
        assertThat(orderStatus()).isEqualTo("EXPIRED");
        assertThat(count("voucher")).isZero();
    }

    @Test
    void expiredEventDoesNotTouchPaidOrder() throws Exception {
        var voucher = issueVoucher("CZK_500", "a@example.com");
        sendWebhook("checkout.session.expired", sessionObj(gateway.lastSessionId()));
        assertThat(orderStatus()).isEqualTo("PAID");
        assertThat(voucher.getId()).isNotNull();
    }

    @Test
    void amountMismatchIssuesNoVoucherAndFlagsAttention() throws Exception {
        checkout("CZK_1000", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        var s = new CheckoutGateway.SessionSnapshot(
                sid, "payment", "czk", 5000L, "paid", "complete", "pi_x", requestOrderId());
        gateway.override(s);

        assertThat(sendWebhook("checkout.session.completed", sessionObj(sid))).isEqualTo(200);

        assertThat(count("voucher")).isZero();
        assertThat(jdbc.queryForObject("select attention_required from voucher_order", Boolean.class)).isTrue();
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'ORDER_MISMATCH'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void currencyMismatchIssuesNoVoucher() throws Exception {
        checkout("CZK_1000", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.override(new CheckoutGateway.SessionSnapshot(
                sid, "payment", "eur", 100000L, "paid", "complete", "pi_x", requestOrderId()));
        sendWebhook("checkout.session.completed", sessionObj(sid));
        assertThat(count("voucher")).isZero();
    }

    @Test
    void foreignSessionWithoutOrderMetadataIsIgnored() throws Exception {
        gateway.override(new CheckoutGateway.SessionSnapshot(
                "cs_foreign", "payment", "czk", 100L, "paid", "complete", "pi_x", null));
        assertThat(sendWebhook("checkout.session.completed", sessionObj("cs_foreign"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("IGNORED");
        assertThat(count("voucher")).isZero();
    }

    @Test
    void unknownEventTypeIsIgnoredWith200() throws Exception {
        assertThat(sendWebhook("customer.created", "{\"id\":\"cus_1\"}")).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("IGNORED");
    }

    @Test
    void liveEventIsIgnoredWhenTestModeIsExpected() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        assertThat(sendWebhook("evt_live", "checkout.session.completed", sessionObj(sid), true)).isEqualTo(200);
        assertThat(count("voucher")).isZero();
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("IGNORED");
    }

    @Test
    void processingFailureReturns500AndRetryDeliversVoucher() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid);
        gateway.failRetrieve = true;
        assertThat(sendWebhook("evt_retry", "checkout.session.completed", sessionObj(sid), false)).isEqualTo(500);
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("FAILED");
        assertThat(count("voucher")).isZero();

        gateway.failRetrieve = false;
        assertThat(sendWebhook("evt_retry", "checkout.session.completed", sessionObj(sid), false)).isEqualTo(200);
        assertThat(count("voucher")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("PROCESSED");
    }

    @Test
    void statusEndpointReflectsPaymentAndShowsVoucherOnlyBriefly() throws Exception {
        checkout("CZK_1000", "jana@example.com");
        String sid = gateway.lastSessionId();

        mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mvc.perform(get("/api/public/orders/status").param("session_id", "cs_does_not_exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.orderRef").doesNotExist());

        // Webhook zatím nedorazil, ale platba už ve Stripe je – status endpoint vystaví voucher sám.
        gateway.markPaid(sid);
        var result = mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.amountCzk").value(1000))
                .andExpect(jsonPath("$.emailMasked").value("j***@e***.com"))
                .andExpect(jsonPath("$.emailStatus").value("PENDING"))
                .andReturn();
        String code = vouchers.findAll().get(0).getCode();
        String formatted = code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8);
        // krátce po zaplacení se voucher na návratové stránce zobrazí (kód, platnost, QR)
        mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.voucher.code").value(formatted))
                .andExpect(jsonPath("$.voucher.amountCzk").value(1000))
                .andExpect(jsonPath("$.voucher.validUntil").value(java.time.LocalDate.now(clock).plusMonths(12).toString()))
                .andExpect(jsonPath("$.voucher.qrDataUri").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")));
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where action = 'VOUCHER_DISPLAYED'", Integer.class))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_log where cast(details as varchar) like ?", Integer.class,
                        "%" + code + "%"))
                .isZero();
        assertThat(count("voucher")).isEqualTo(1);

        // po uplynutí okna (60 min) se kód už nevrací, stav ano
        clock.setInstant(clock.instant().plus(java.time.Duration.ofMinutes(61)));
        String late = mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(late).doesNotContain(code).contains("\"voucher\":null");
        clock.reset();

        mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"PAID\"")));
        assertThat(count("voucher")).isEqualTo(1);
    }

    @Test
    void voucherIsNotShownForRefundedOrCancelledVoucher() throws Exception {
        issueVoucher("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        jdbc.update("update voucher set status = 'CANCELLED'");
        mvc.perform(get("/api/public/orders/status").param("session_id", sid))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.voucher").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void statusEndpointIsRateLimited() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/api/public/orders/status").param("session_id", "cs_x")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/public/orders/status").param("session_id", "cs_x"))
                .andExpect(status().isTooManyRequests());
    }

    private String requestOrderId() {
        return gateway.lastRequest().orderId().toString();
    }

    @Test
    void helperUuidIsUnique() {
        assertThat(UUID.randomUUID()).isNotEqualTo(UUID.randomUUID());
    }
}
