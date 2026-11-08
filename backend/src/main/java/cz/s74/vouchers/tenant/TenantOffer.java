package cz.s74.vouchers.tenant;

import cz.s74.vouchers.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Položka ceníku podniku (hodnota voucheru). Cena se bere výhradně odtud, nikdy z požadavku. */
@Entity
@Table(name = "tenant_offer")
public class TenantOffer extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "offer_code", nullable = false)
    private String offerCode;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    protected TenantOffer() {}

    public TenantOffer(UUID id, UUID tenantId, String offerCode, long amountMinor, int sortOrder) {
        super(id);
        this.tenantId = tenantId;
        this.offerCode = offerCode;
        this.amountMinor = amountMinor;
        this.sortOrder = sortOrder;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate(int sortOrder) {
        this.active = true;
        this.sortOrder = sortOrder;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getOfferCode() {
        return offerCode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
