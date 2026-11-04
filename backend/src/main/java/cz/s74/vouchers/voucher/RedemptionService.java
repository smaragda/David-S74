package cz.s74.vouchers.voucher;

import cz.s74.vouchers.audit.AuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RedemptionService {

    public enum Result {
        /** Uplatněno touto žádostí. */
        REDEEMED,
        /** Voucher už není platný (uplatněn / zrušen / vypršel) – uplatnění zamítnuto. */
        REJECTED
    }

    private final VoucherRepository vouchers;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RedemptionService(VoucherRepository vouchers, AuditService audit, TransactionTemplate tx, Clock clock) {
        this.vouchers = vouchers;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Uplatnění jedním podmíněným {@code UPDATE … WHERE status = 'ACTIVE' AND valid_until >= dnes}:
     * při souběhu uspěje právě jeden požadavek, ostatní dostanou 0 změněných řádků.
     */
    public Result redeem(UUID voucherId, UUID staffId, String note, String ip) {
        String cleanNote = note == null || note.isBlank() ? null : trim(note.strip(), 255);
        return tx.execute(s -> {
            Instant now = Instant.now(clock);
            LocalDate today = LocalDate.now(clock);
            int changed = vouchers.redeem(voucherId, staffId, cleanNote, now, today);
            Voucher v = vouchers.findById(voucherId).orElseThrow();
            Map<String, Object> details = new HashMap<>();
            details.put("code", cz.s74.vouchers.common.Masking.code(v.getCode()));
            if (changed == 1) {
                if (cleanNote != null) {
                    details.put("note", cleanNote);
                }
                audit.staff(staffId, "VOUCHER_REDEEMED", voucherId, v.getOrderId(), ip, details);
                return Result.REDEEMED;
            }
            details.put("state", v.effectiveStatus(today).name());
            audit.staff(staffId, "REDEMPTION_REJECTED", voucherId, v.getOrderId(), ip, details);
            return Result.REJECTED;
        });
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
