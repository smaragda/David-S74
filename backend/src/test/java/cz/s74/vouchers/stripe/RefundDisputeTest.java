package cz.s74.vouchers.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import cz.s74.vouchers.support.AbstractIT;
import org.junit.jupiter.api.Test;

class RefundDisputeTest extends AbstractIT {

    private String charge(String pi, long amount, long refunded) {
        return "{\"id\":\"ch_1\",\"object\":\"charge\",\"payment_intent\":\"" + pi + "\",\"amount\":" + amount
                + ",\"amount_refunded\":" + refunded + "}";
    }

    private String dispute(String pi, String status) {
        return "{\"id\":\"dp_1\",\"object\":\"dispute\",\"payment_intent\":\"" + pi + "\",\"status\":\"" + status + "\"}";
    }

    private String pi() {
        return gateway.paymentIntentOf(gateway.lastSessionId());
    }

    private String orderField(String col) {
        return jdbc.queryForObject("select cast(" + col + " as varchar) from voucher_order", String.class);
    }

    private String voucherField(String col) {
        return jdbc.queryForObject("select cast(" + col + " as varchar) from voucher", String.class);
    }

    @Test
    void fullRefundCancelsActiveVoucher() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        assertThat(sendWebhook("charge.refunded", charge(pi(), 100000, 100000))).isEqualTo(200);

        assertThat(orderField("status")).isEqualTo("REFUNDED");
        assertThat(orderField("refunded_amount_minor")).isEqualTo("100000");
        assertThat(voucherField("status")).isEqualTo("CANCELLED");
        assertThat(voucherField("cancel_reason")).isEqualTo("REFUND");
        assertThat(orderField("attention_required")).isEqualTo("false");
    }

    @Test
    void fullRefundOfRedeemedVoucherFlagsAttention() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        jdbc.update("update voucher set status = 'REDEEMED', redeemed_at = now()");
        sendWebhook("charge.refunded", charge(pi(), 100000, 100000));

        assertThat(orderField("status")).isEqualTo("REFUNDED");
        assertThat(voucherField("status")).isEqualTo("REDEEMED");
        assertThat(orderField("attention_required")).isEqualTo("true");
        assertThat(orderField("attention_reason")).contains("použitého voucheru");
    }

    @Test
    void partialRefundOnlyFlagsAttention() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        sendWebhook("charge.refunded", charge(pi(), 100000, 30000));

        assertThat(orderField("status")).isEqualTo("PAID");
        assertThat(orderField("refunded_amount_minor")).isEqualTo("30000");
        assertThat(voucherField("status")).isEqualTo("ACTIVE");
        assertThat(orderField("attention_required")).isEqualTo("true");
    }

    @Test
    void refundIsIdempotent() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        sendWebhook("charge.refunded", charge(pi(), 100000, 100000));
        sendWebhook("charge.refunded", charge(pi(), 100000, 100000));
        assertThat(orderField("status")).isEqualTo("REFUNDED");
        assertThat(count("voucher")).isEqualTo(1);
    }

    @Test
    void refundOfUnknownPaymentIntentIsIgnored() throws Exception {
        assertThat(sendWebhook("charge.refunded", charge("pi_unknown", 100, 100))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from stripe_event", String.class)).isEqualTo("IGNORED");
    }

    @Test
    void refundBeforeOrderIsPaidIsRetried() throws Exception {
        checkout("CZK_500", "a@example.com");
        String sid = gateway.lastSessionId();
        gateway.markPaid(sid); // PI známe ze Stripe, ale webhook completed ještě nedorazil
        assertThat(sendWebhook("charge.refunded", charge(gateway.paymentIntentOf(sid), 50000, 50000))).isEqualTo(500);
        assertThat(count("voucher")).isZero();
    }

    @Test
    void disputeCreatedCancelsVoucherAndWonRestoresOrder() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        sendWebhook("charge.dispute.created", dispute(pi(), "needs_response"));

        assertThat(orderField("status")).isEqualTo("DISPUTED");
        assertThat(voucherField("status")).isEqualTo("CANCELLED");
        assertThat(voucherField("cancel_reason")).isEqualTo("DISPUTE");
        assertThat(orderField("attention_required")).isEqualTo("true");

        sendWebhook("charge.dispute.closed", dispute(pi(), "won"));
        assertThat(orderField("status")).isEqualTo("PAID");
        assertThat(voucherField("status")).isEqualTo("CANCELLED"); // reaktivaci rozhodne vedoucí
        assertThat(orderField("attention_reason")).contains("vyhrán");
    }

    @Test
    void disputeLostKeepsOrderDisputed() throws Exception {
        issueVoucher("CZK_1000", "a@example.com");
        sendWebhook("charge.dispute.created", dispute(pi(), "needs_response"));
        sendWebhook("charge.dispute.closed", dispute(pi(), "lost"));
        assertThat(orderField("status")).isEqualTo("DISPUTED");
        assertThat(orderField("attention_required")).isEqualTo("true");
    }
}
