package cz.s74.vouchers.admin;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.common.EmailAddresses;
import cz.s74.vouchers.common.Masking;
import cz.s74.vouchers.email.EmailDelivery;
import cz.s74.vouchers.email.EmailDeliveryRepository;
import cz.s74.vouchers.email.EmailOutbox;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodes;
import cz.s74.vouchers.voucher.VoucherRepository;
import cz.s74.vouchers.voucher.VoucherStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Zásahy vedoucího. Každý vyžaduje důvod a zapisuje audit; oprávnění (MANAGER) hlídá Security. */
@Service
public class VoucherAdminService {

    private static final Pattern ORDER_REF = Pattern.compile("^[0-9a-fA-F]{8}$");

    private final VoucherRepository vouchers;
    private final OrderRepository orders;
    private final EmailDeliveryRepository deliveries;
    private final EmailOutbox outbox;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public VoucherAdminService(
            VoucherRepository vouchers,
            OrderRepository orders,
            EmailDeliveryRepository deliveries,
            EmailOutbox outbox,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock) {
        this.vouchers = vouchers;
        this.orders = orders;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public record Detail(
            Voucher voucher,
            VoucherOrder order,
            List<EmailDelivery> deliveries,
            List<AuditService.AuditEntry> history) {}

    /** Hledání podle kódu (normalizovaného), e-mailu (case-insensitive) nebo čísla objednávky (8 znaků). */
    public List<Voucher> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        Map<UUID, Voucher> found = new LinkedHashMap<>();
        Optional<String> code = VoucherCodes.normalize(q);
        if (q.contains("@")) {
            vouchers.searchByCodeOrEmail("-", q.toLowerCase(Locale.ROOT)).forEach(v -> found.put(v.getId(), v));
        }
        code.flatMap(vouchers::findByCode).ifPresent(v -> found.put(v.getId(), v));
        if (ORDER_REF.matcher(q).matches()) {
            vouchers.searchByOrderRef(q.toUpperCase(Locale.ROOT)).forEach(v -> found.put(v.getId(), v));
        }
        return new ArrayList<>(found.values());
    }

    public Detail detail(UUID voucherId) {
        Voucher v = vouchers.findById(voucherId).orElseThrow(() -> new BusinessRuleException("Voucher neexistuje."));
        VoucherOrder order = orders.findById(v.getOrderId()).orElseThrow();
        return new Detail(v, order, deliveries.findByVoucherIdOrderByCreatedAtAsc(v.getId()), audit.forVoucher(v.getId(), order.getId()));
    }

    public void resend(UUID actor, UUID voucherId, String newEmail, String reason, String ip) {
        String why = requireReason(reason);
        tx.executeWithoutResult(s -> {
            Voucher v = load(voucherId);
            VoucherOrder order = orders.lockById(v.getOrderId()).orElseThrow();
            String recipient = order.getCustomerEmail();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", why);
            if (newEmail != null && !newEmail.isBlank()) {
                String corrected = EmailAddresses.normalize(newEmail)
                        .orElseThrow(() -> new BusinessRuleException("Zadejte platnou e-mailovou adresu."));
                if (!corrected.equalsIgnoreCase(recipient)) {
                    details.put("from", recipient);
                    details.put("to", corrected);
                    order.setCustomerEmail(corrected, Instant.now(clock));
                    orders.save(order);
                    recipient = corrected;
                }
            }
            outbox.queueResend(v.getId(), recipient, actor);
            details.put("recipient", Masking.email(recipient));
            audit.staff(actor, "EMAIL_RESEND_REQUESTED", v.getId(), order.getId(), ip, details);
        });
    }

    public void cancel(UUID actor, UUID voucherId, String reason, String ip) {
        String why = requireReason(reason);
        tx.executeWithoutResult(s -> {
            Voucher v = load(voucherId);
            if (v.getStatus() != VoucherStatus.ACTIVE) {
                throw new BusinessRuleException("Zrušit lze jen platný (neuplatněný) voucher.");
            }
            v.cancel(actor, why, Instant.now(clock));
            vouchers.saveAndFlush(v);
            audit.staff(actor, "VOUCHER_CANCELLED", v.getId(), v.getOrderId(), ip, Map.of("reason", why));
        });
    }

    public void reactivate(UUID actor, UUID voucherId, String reason, String ip) {
        String why = requireReason(reason);
        tx.executeWithoutResult(s -> {
            Voucher v = load(voucherId);
            if (v.getStatus() != VoucherStatus.CANCELLED) {
                throw new BusinessRuleException("Reaktivovat lze jen zrušený voucher.");
            }
            v.reactivate();
            vouchers.saveAndFlush(v);
            audit.staff(actor, "VOUCHER_REACTIVATED", v.getId(), v.getOrderId(), ip, Map.of("reason", why));
        });
    }

    /** Oprava chyby mezi uplatněním a pokladnou: REDEEMED → ACTIVE. */
    public void revertRedemption(UUID actor, UUID voucherId, String reason, String ip) {
        String why = requireReason(reason);
        tx.executeWithoutResult(s -> {
            Voucher v = load(voucherId);
            if (v.getStatus() != VoucherStatus.REDEEMED) {
                throw new BusinessRuleException("Vrátit lze jen uplatněný voucher.");
            }
            v.reactivate();
            vouchers.saveAndFlush(v);
            audit.staff(actor, "REDEMPTION_REVERTED", v.getId(), v.getOrderId(), ip, Map.of("reason", why));
        });
    }

    public void extendValidity(UUID actor, UUID voucherId, LocalDate newValidUntil, String reason, String ip) {
        String why = requireReason(reason);
        if (newValidUntil == null) {
            throw new BusinessRuleException("Vyplňte nové datum platnosti.");
        }
        tx.executeWithoutResult(s -> {
            Voucher v = load(voucherId);
            if (newValidUntil.isBefore(LocalDate.now(clock))) {
                throw new BusinessRuleException("Nové datum platnosti nesmí být v minulosti.");
            }
            LocalDate old = v.getValidUntil();
            v.extendTo(newValidUntil);
            vouchers.saveAndFlush(v);
            audit.staff(actor, "VALIDITY_EXTENDED", v.getId(), v.getOrderId(), ip,
                    Map.of("reason", why, "from", old.toString(), "to", newValidUntil.toString()));
        });
    }

    public void resolveAttention(UUID actor, UUID orderId, String note, String ip) {
        String why = requireReason(note);
        tx.executeWithoutResult(s -> {
            VoucherOrder order = orders.lockById(orderId).orElseThrow(() -> new BusinessRuleException("Objednávka neexistuje."));
            String previous = order.getAttentionReason();
            order.resolveAttention(Instant.now(clock));
            orders.save(order);
            audit.staff(actor, "ATTENTION_RESOLVED", vouchers.findByOrderId(orderId).map(Voucher::getId).orElse(null),
                    orderId, ip, Map.of("note", why, "was", previous == null ? "" : previous));
        });
    }

    private Voucher load(UUID id) {
        return vouchers.findById(id).orElseThrow(() -> new BusinessRuleException("Voucher neexistuje."));
    }

    static String requireReason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.length() < 3) {
            throw new BusinessRuleException("Uveďte důvod zásahu (alespoň 3 znaky).");
        }
        return r.length() > 255 ? r.substring(0, 255) : r;
    }
}
