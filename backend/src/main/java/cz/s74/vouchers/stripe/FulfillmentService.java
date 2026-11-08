package cz.s74.vouchers.stripe;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.email.EmailDelivery;
import cz.s74.vouchers.email.EmailDeliveryRepository;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.OrderStatus;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.tenant.TenantService;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodeGenerator;
import cz.s74.vouchers.voucher.VoucherRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Jediné místo, kde vzniká voucher. Volá se z webhooku i ze status endpointu, je idempotentní:
 * souběh serializuje zámek řádku objednávky, poslední pojistkou je {@code UNIQUE(voucher.order_id)}.
 */
@Service
public class FulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);
    private static final Set<OrderStatus> TERMINAL_PAID = Set.of(OrderStatus.PAID, OrderStatus.REFUNDED, OrderStatus.DISPUTED);

    public enum Outcome {
        /** Vystaven voucher. */
        ISSUED,
        /** Platba se zpracovává (odložená metoda). */
        PROCESSING,
        /** Objednávka už byla zaplacená dřív. */
        ALREADY_PAID,
        /** Platba zatím nedorazila. */
        NOT_PAID,
        /** Nesoulad částky / měny / session – bez voucheru, objednávka označena k řešení. */
        MISMATCH,
        /** Cizí nebo neexistující session. */
        IGNORED
    }

    private final CheckoutGateway gateway;
    private final OrderRepository orders;
    private final VoucherRepository vouchers;
    private final EmailDeliveryRepository deliveries;
    private final VoucherCodeGenerator codes;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TenantService tenants;

    public FulfillmentService(
            CheckoutGateway gateway,
            OrderRepository orders,
            VoucherRepository vouchers,
            EmailDeliveryRepository deliveries,
            VoucherCodeGenerator codes,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock,
            TenantService tenants) {
        this.gateway = gateway;
        this.orders = orders;
        this.vouchers = vouchers;
        this.deliveries = deliveries;
        this.codes = codes;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.tenants = tenants;
    }

    /**
     * @param stripeAccountId účet, ze kterého session pochází (připojený účet podniku; null = platforma).
     *     Session se čte z tohoto účtu a musí patřit objednávce založené na stejném účtu.
     */
    public Outcome fulfill(String stripeAccountId, String sessionId) {
        Optional<CheckoutGateway.SessionSnapshot> snapshot = gateway.retrieveSession(stripeAccountId, sessionId);
        if (snapshot.isEmpty()) {
            return Outcome.IGNORED;
        }
        CheckoutGateway.SessionSnapshot session = snapshot.get();
        UUID orderId = parse(session.orderId());
        if (orderId == null) {
            return Outcome.IGNORED; // cizí session
        }
        return tx.execute(status -> apply(orderId, stripeAccountId, session));
    }

    private Outcome apply(UUID orderId, String stripeAccountId, CheckoutGateway.SessionSnapshot session) {
        Optional<VoucherOrder> locked = orders.lockById(orderId);
        if (locked.isEmpty()) {
            if (stripeAccountId != null) {
                // Připojený účet může mít i vlastní (ne naše) Checkout Sessions s podobnými metadaty.
                log.warn("Session {} on account {} references unknown order {}", session.id(), stripeAccountId, orderId);
                return Outcome.IGNORED;
            }
            throw new IllegalStateException("Order " + orderId + " for Stripe session not found");
        }
        VoucherOrder order = locked.get();
        if (!Objects.equals(order.getStripeAccountId(), stripeAccountId)) {
            // Session z jiného účtu, než na kterém objednávka vznikla – nikdy nevystavit voucher cizího podniku.
            log.error("Order {} belongs to account {} but session {} came from account {}",
                    order.orderRef(), order.getStripeAccountId(), session.id(), stripeAccountId);
            return Outcome.IGNORED;
        }
        Instant now = Instant.now(clock);

        if (TERMINAL_PAID.contains(order.getStatus())) {
            return Outcome.ALREADY_PAID;
        }
        if (order.getStripeCheckoutSessionId() == null) {
            order.setStripeCheckoutSessionId(session.id());
        }
        String mismatch = mismatch(order, session);
        if (mismatch != null) {
            order.flagAttention("Nesoulad platby: " + mismatch, now);
            orders.save(order);
            audit.stripe("ORDER_MISMATCH", null, order.getId(), Map.of("reason", mismatch));
            log.error("Order {} does not match Stripe session {}: {}", order.orderRef(), session.id(), mismatch);
            return Outcome.MISMATCH;
        }

        if ("paid".equals(session.paymentStatus())) {
            order.setStripePaymentIntentId(session.paymentIntentId());
            order.setPaidAt(now);
            order.changeStatus(OrderStatus.PAID, now);
            orders.save(order);

            int validityMonths = tenants.settings(order.getTenantId()).validityMonths();
            Voucher voucher = vouchers.saveAndFlush(new Voucher(
                    UUID.randomUUID(),
                    order.getTenantId(),
                    order.getId(),
                    uniqueCode(),
                    order.getAmountMinor(),
                    order.getCurrency(),
                    now,
                    LocalDate.now(clock).plusMonths(validityMonths)));
            deliveries.save(new EmailDelivery(
                    UUID.randomUUID(), voucher.getId(), EmailDelivery.Kind.ISSUED, order.getCustomerEmail(), null, now));
            audit.stripe("PAYMENT_CONFIRMED", voucher.getId(), order.getId(), Map.of("sessionId", session.id()));
            audit.system("VOUCHER_ISSUED", voucher.getId(), order.getId(), Map.of());
            log.info("Voucher issued for order {}", order.orderRef());
            return Outcome.ISSUED;
        }
        if ("complete".equals(session.status()) && "unpaid".equals(session.paymentStatus())) {
            if (order.getStatus() != OrderStatus.PAYMENT_PROCESSING) {
                order.changeStatus(OrderStatus.PAYMENT_PROCESSING, now);
                orders.save(order);
            }
            return Outcome.PROCESSING;
        }
        return Outcome.NOT_PAID;
    }

    private String mismatch(VoucherOrder order, CheckoutGateway.SessionSnapshot s) {
        if (!order.getStripeCheckoutSessionId().equals(s.id())) {
            return "session mismatch";
        }
        if (!"payment".equals(s.mode())) {
            return "unexpected session mode";
        }
        if (s.currency() == null || !s.currency().equalsIgnoreCase(order.getCurrency())) {
            return "currency mismatch";
        }
        if (s.amountTotal() == null || s.amountTotal() != order.getAmountMinor()) {
            return "amount mismatch (expected " + order.getAmountMinor() + ", got " + s.amountTotal() + ")";
        }
        return null;
    }

    private String uniqueCode() {
        for (int i = 0; i < 5; i++) {
            String code = codes.generate();
            if (vouchers.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate unique voucher code");
    }

    static UUID parse(String id) {
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
