package cz.s74.vouchers.voucher;

import cz.s74.vouchers.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "voucher")
public class Voucher extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VoucherStatus status;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "redeemed_by")
    private UUID redeemedBy;

    @Column(name = "redemption_note")
    private String redemptionNote;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Version
    private long version;

    protected Voucher() {}

    public Voucher(
            UUID id,
            UUID tenantId,
            UUID orderId,
            String code,
            long amountMinor,
            String currency,
            Instant issuedAt,
            LocalDate validUntil) {
        super(id);
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.code = code;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.issuedAt = issuedAt;
        this.validUntil = validUntil;
        this.status = VoucherStatus.ACTIVE;
    }

    /** Efektivní stav pro UI: REDEEMED &gt; CANCELLED &gt; EXPIRED (odvozeně) &gt; ACTIVE. */
    public EffectiveStatus effectiveStatus(LocalDate today) {
        if (status == VoucherStatus.REDEEMED) {
            return EffectiveStatus.REDEEMED;
        }
        if (status == VoucherStatus.CANCELLED) {
            return EffectiveStatus.CANCELLED;
        }
        return today.isAfter(validUntil) ? EffectiveStatus.EXPIRED : EffectiveStatus.ACTIVE;
    }

    public void cancel(UUID by, String reason, Instant now) {
        this.status = VoucherStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.cancelReason = reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    /** Návrat do stavu ACTIVE (reaktivace i vrácení uplatnění) – smaže stopy předchozího stavu. */
    public void reactivate() {
        this.status = VoucherStatus.ACTIVE;
        this.redeemedAt = null;
        this.redeemedBy = null;
        this.redemptionNote = null;
        this.cancelledAt = null;
        this.cancelledBy = null;
        this.cancelReason = null;
    }

    public void extendTo(LocalDate newValidUntil) {
        this.validUntil = newValidUntil;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getCode() {
        return code;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public VoucherStatus getStatus() {
        return status;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public UUID getRedeemedBy() {
        return redeemedBy;
    }

    public String getRedemptionNote() {
        return redemptionNote;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public long getVersion() {
        return version;
    }

    public enum EffectiveStatus {
        ACTIVE,
        REDEEMED,
        EXPIRED,
        CANCELLED
    }
}
