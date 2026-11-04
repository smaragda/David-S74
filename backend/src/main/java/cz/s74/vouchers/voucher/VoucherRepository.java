package cz.s74.vouchers.voucher;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByCode(String code);

    Optional<Voucher> findByOrderId(UUID orderId);

    /**
     * Atomické uplatnění: uspěje právě jeden souběžný požadavek. Vrací počet změněných řádků
     * (1 = uplatněno, 0 = voucher už není ACTIVE nebo vypršel).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value = "update voucher set status = 'REDEEMED', redeemed_at = :now, redeemed_by = :staffId,"
                    + " redemption_note = :note, version = version + 1"
                    + " where id = :id and status = 'ACTIVE' and valid_until >= :today")
    int redeem(
            @Param("id") UUID id,
            @Param("staffId") UUID staffId,
            @Param("note") String note,
            @Param("now") Instant now,
            @Param("today") LocalDate today);

    @Query(
            "select v from Voucher v where v.code = :code or v.orderId in"
                    + " (select o.id from VoucherOrder o where lower(o.customerEmail) = :email)")
    List<Voucher> searchByCodeOrEmail(@Param("code") String code, @Param("email") String email);

    @Query("select v from Voucher v where v.orderId in (select o.id from VoucherOrder o where"
            + " upper(substring(cast(o.id as string), 1, 8)) = :ref)")
    List<Voucher> searchByOrderRef(@Param("ref") String ref);

    @Query("select v from Voucher v where v.status = 'ACTIVE' and v.validUntil >= :from and v.validUntil <= :to")
    List<Voucher> findExpiringBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
