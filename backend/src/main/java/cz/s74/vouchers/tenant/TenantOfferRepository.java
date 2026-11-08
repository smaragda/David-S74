package cz.s74.vouchers.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantOfferRepository extends JpaRepository<TenantOffer, UUID> {

    List<TenantOffer> findByTenantIdAndActiveTrueOrderBySortOrderAscAmountMinorAsc(UUID tenantId);

    List<TenantOffer> findByTenantId(UUID tenantId);
}
