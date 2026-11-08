package cz.s74.vouchers.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findByStripeAccountId(String stripeAccountId);

    boolean existsBySlug(String slug);

    List<Tenant> findAllByOrderByDisplayNameAsc();
}
