package cz.s74.vouchers.tenant;

import cz.s74.vouchers.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Podnik, který prodává vouchery (klient platformy). Nastavení s hodnotou {@code null} se doplňují z konfigurace
 * (jen u výchozího tenanta) nebo z výchozích hodnot platformy – viz {@link TenantSettings}.
 */
@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    public enum Status {
        /** Smí prodávat (pokud je platební účet připravený). */
        ACTIVE,
        /** Nový prodej pozastaven správcem platformy; už prodané vouchery obsluha dál uplatňuje. */
        SUSPENDED
    }

    public enum PaymentMode {
        /** Platby jdou na Stripe účet platformy (jen výchozí tenant S74 z doby před Connect). */
        PLATFORM,
        /** Přímé platby (direct charges) na připojený Stripe účet podniku. */
        CONNECT
    }

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "company_id")
    private String companyId;

    @Column(name = "address")
    private String address;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "reply_to")
    private String replyTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false)
    private PaymentMode paymentMode;

    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    @Column(name = "stripe_card_payments")
    private String stripeCardPayments;

    @Column(name = "stripe_requirements_due", nullable = false)
    private int stripeRequirementsDue;

    @Column(name = "stripe_status_checked_at")
    private Instant stripeStatusCheckedAt;

    @Column(name = "frontend_base_url")
    private String frontendBaseUrl;

    @Column(name = "cors_allowed_origins")
    private String corsAllowedOrigins;

    @Column(name = "terms_url")
    private String termsUrl;

    @Column(name = "terms_version")
    private String termsVersion;

    @Column(name = "validity_months")
    private Integer validityMonths;

    @Column(name = "show_on_return_minutes")
    private Integer showOnReturnMinutes;

    @Column(name = "brand_color")
    private String brandColor;

    @Column(name = "application_fee_bps", nullable = false)
    private int applicationFeeBps;

    @Column(name = "application_fee_fixed_minor", nullable = false)
    private long applicationFeeFixedMinor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(UUID id, String slug, String displayName, PaymentMode paymentMode, Instant now) {
        super(id);
        this.slug = slug;
        this.displayName = displayName;
        this.paymentMode = paymentMode;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isDefault() {
        return Tenants.DEFAULT_ID.equals(getId());
    }

    /** Prodej je možný: tenant je aktivní a jeho platební účet smí přijímat karty. */
    public boolean salesEnabled() {
        if (status != Status.ACTIVE) {
            return false;
        }
        return paymentMode == PaymentMode.PLATFORM || (stripeAccountId != null && "active".equals(stripeCardPayments));
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void updateProfile(
            String displayName,
            String legalName,
            String companyId,
            String address,
            String contactEmail,
            String replyTo,
            Instant now) {
        this.displayName = displayName;
        this.legalName = legalName;
        this.companyId = companyId;
        this.address = address;
        this.contactEmail = contactEmail;
        this.replyTo = replyTo;
        this.updatedAt = now;
    }

    public void updateSales(
            String frontendBaseUrl,
            String corsAllowedOrigins,
            String termsUrl,
            String termsVersion,
            Integer validityMonths,
            Integer showOnReturnMinutes,
            String brandColor,
            Instant now) {
        this.frontendBaseUrl = frontendBaseUrl;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.termsUrl = termsUrl;
        this.termsVersion = termsVersion;
        this.validityMonths = validityMonths;
        this.showOnReturnMinutes = showOnReturnMinutes;
        this.brandColor = brandColor;
        this.updatedAt = now;
    }

    public void updateFees(int bps, long fixedMinor, Instant now) {
        this.applicationFeeBps = bps;
        this.applicationFeeFixedMinor = fixedMinor;
        this.updatedAt = now;
    }

    public void changeStatus(Status status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public void attachStripeAccount(String accountId, Instant now) {
        this.stripeAccountId = accountId;
        this.stripeCardPayments = null;
        this.stripeRequirementsDue = 0;
        this.updatedAt = now;
    }

    public void switchPaymentMode(PaymentMode mode, Instant now) {
        this.paymentMode = mode;
        this.updatedAt = now;
    }

    public void recordStripeStatus(String cardPayments, int requirementsDue, Instant now) {
        this.stripeCardPayments = cardPayments;
        this.stripeRequirementsDue = requirementsDue;
        this.stripeStatusCheckedAt = now;
        this.updatedAt = now;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getAddress() {
        return address;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getReplyTo() {
        return replyTo;
    }

    public Status getStatus() {
        return status;
    }

    public PaymentMode getPaymentMode() {
        return paymentMode;
    }

    public String getStripeAccountId() {
        return stripeAccountId;
    }

    public String getStripeCardPayments() {
        return stripeCardPayments;
    }

    public int getStripeRequirementsDue() {
        return stripeRequirementsDue;
    }

    public Instant getStripeStatusCheckedAt() {
        return stripeStatusCheckedAt;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public String getTermsUrl() {
        return termsUrl;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public Integer getValidityMonths() {
        return validityMonths;
    }

    public Integer getShowOnReturnMinutes() {
        return showOnReturnMinutes;
    }

    public String getBrandColor() {
        return brandColor;
    }

    public int getApplicationFeeBps() {
        return applicationFeeBps;
    }

    public long getApplicationFeeFixedMinor() {
        return applicationFeeFixedMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
