package cz.s74.vouchers.order;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<VoucherOrder, UUID> {

    Optional<VoucherOrder> findByStripeCheckoutSessionId(String sessionId);

    Optional<VoucherOrder> findByStripePaymentIntentId(String paymentIntentId);

    /** SELECT … FOR UPDATE – serializuje souběžné zpracování téže objednávky. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VoucherOrder o where o.id = :id")
    Optional<VoucherOrder> lockById(@Param("id") UUID id);

    List<VoucherOrder> findByAttentionRequiredTrueOrderByUpdatedAtDesc();

    @Query("select o from VoucherOrder o where upper(substring(cast(o.id as string), 1, 8)) = :ref")
    List<VoucherOrder> findByOrderRef(@Param("ref") String ref);
}
