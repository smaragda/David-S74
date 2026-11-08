package cz.s74.vouchers.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {

    Optional<StaffUser> findByUsernameIgnoreCase(String username);

    boolean existsByRoleAndEnabledTrue(StaffRole role);

    boolean existsByTenantIdAndRoleAndEnabledTrue(UUID tenantId, StaffRole role);

    long countByTenantIdAndRoleAndEnabledTrue(UUID tenantId, StaffRole role);

    List<StaffUser> findAllByTenantIdOrderByUsernameAsc(UUID tenantId);

    List<StaffUser> findAllByRoleOrderByUsernameAsc(StaffRole role);

    Optional<StaffUser> findByIdAndTenantId(UUID id, UUID tenantId);
}
