package cz.s74.vouchers.order;

import cz.s74.vouchers.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "voucher_order")
public class VoucherOrder extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Stripe účet, na kterém se platí (null = platformní účet). Snímek z doby objednávky. */
    @Column(name = "stripe_account_id", updatable = false)
    private String stripeAccountId;

    @Column(name = "application_fee_minor", nullable = false, updatable = false)
    private long applicationFeeMinor;

    @Column(name = "offer_id", nullable = false)
    private String offerId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "CZK";

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "terms_version", nullable = false)
    private String termsVersion;

    @Column(name = "terms_accepted_at", nullable = false)
    private Instant termsAcceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "stripe_checkout_session_id")
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "attention_required", nullable = false)
    private boolean attentionRequired;

    @Column(name = "attention_reason")
    private String attentionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected VoucherOrder() {}

    public VoucherOrder(
            UUID id,
            UUID tenantId,
            String stripeAccountId,
            long applicationFeeMinor,
            String offerId,
            long amountMinor,
            String customerEmail,
            String termsVersion,
            Instant now) {
        super(id);
        this.tenantId = tenantId;
        this.stripeAccountId = stripeAccountId;
        this.applicationFeeMinor = applicationFeeMinor;
        this.offerId = offerId;
        this.amountMinor = amountMinor;
        this.customerEmail = customerEmail;
        this.termsVersion = termsVersion;
        this.termsAcceptedAt = now;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Prvních 8 znaků UUID velkými písmeny – číslo objednávky pro komunikaci se zákazníkem. */
    public String orderRef() {
        return getId().toString().substring(0, 8).toUpperCase();
    }

    public void flagAttention(String reason, Instant now) {
        this.attentionRequired = true;
        this.attentionReason = reason.length() > 255 ? reason.substring(0, 255) : reason;
        this.updatedAt = now;
    }

    public void resolveAttention(Instant now) {
        this.attentionRequired = false;
        this.attentionReason = null;
        this.updatedAt = now;
    }

    public void changeStatus(OrderStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getStripeAccountId() {
        return stripeAccountId;
    }

    public long getApplicationFeeMinor() {
        return applicationFeeMinor;
    }

    public String getOfferId() {
        return offerId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail, Instant now) {
        this.customerEmail = customerEmail;
        this.updatedAt = now;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getStripeCheckoutSessionId() {
        return stripeCheckoutSessionId;
    }

    public void setStripeCheckoutSessionId(String id) {
        this.stripeCheckoutSessionId = id;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public void setStripePaymentIntentId(String id) {
        this.stripePaymentIntentId = id;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public void setRefundedAmountMinor(long refundedAmountMinor) {
        this.refundedAmountMinor = refundedAmountMinor;
    }

    public boolean isAttentionRequired() {
        return attentionRequired;
    }

    public String getAttentionReason() {
        return attentionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }
}
