package cz.s74.vouchers.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    @Query(
            nativeQuery = true,
            value = "select * from email_delivery where status = 'PENDING' and next_attempt_at <= :now"
                    + " order by created_at limit :limit for update skip locked")
    List<EmailDelivery> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    List<EmailDelivery> findByVoucherIdOrderByCreatedAtAsc(UUID voucherId);

    /** Po pádu během odesílání: SENDING starší než hranice zpět na PENDING. */
    @Modifying
    @Query("update EmailDelivery d set d.status = 'PENDING' where d.status = 'SENDING' and d.nextAttemptAt < :before")
    int resetStuck(@Param("before") Instant before);

    @Query("select d from EmailDelivery d where d.status = 'FAILED' and d.voucherId in"
            + " (select v.id from Voucher v where v.tenantId = :tenantId) order by d.createdAt desc")
    List<EmailDelivery> findFailed(@Param("tenantId") UUID tenantId);
}
