package cz.s74.vouchers.staff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {

    Optional<StaffUser> findByUsernameIgnoreCase(String username);

    boolean existsByRoleAndEnabledTrue(StaffRole role);

    long countByRoleAndEnabledTrue(StaffRole role);

    List<StaffUser> findAllByOrderByUsernameAsc();
}
