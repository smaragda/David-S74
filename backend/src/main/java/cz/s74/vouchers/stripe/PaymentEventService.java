package cz.s74.vouchers.stripe;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.OrderStatus;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherRepository;
import cz.s74.vouchers.voucher.VoucherStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reakce na expiraci, selhání odložené platby, refundy a spory (analýza kap. 10.5). Vše idempotentní. */
@Service
public class PaymentEventService {

    private final OrderRepository orders;
    private final VoucherRepository vouchers;
    private final CheckoutGateway gateway;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentEventService(
            OrderRepository orders,
            VoucherRepository vouchers,
            CheckoutGateway gateway,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.gateway = gateway;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** {@code checkout.session.expired}: jen PENDING_PAYMENT → EXPIRED. */
    public boolean sessionExpired(String account, String sessionId) {
        return changeBySession(account, sessionId, OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED, null);
    }

    /** {@code checkout.session.async_payment_failed}: PENDING / PROCESSING → FAILED. */
    public boolean asyncPaymentFailed(String account, String sessionId) {
        boolean a = changeBySession(
                account, sessionId, OrderStatus.PAYMENT_PROCESSING, OrderStatus.FAILED, "ASYNC_PAYMENT_FAILED");
        boolean b = changeBySession(
                account, sessionId, OrderStatus.PENDING_PAYMENT, OrderStatus.FAILED, "ASYNC_PAYMENT_FAILED");
        return a || b;
    }

    private boolean changeBySession(
            String account, String sessionId, OrderStatus from, OrderStatus to, String auditAction) {
        Optional<VoucherOrder> found = orders.findByStripeCheckoutSessionId(sessionId)
                .filter(o -> Objects.equals(o.getStripeAccountId(), account));
        if (found.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(tx.execute(s -> {
            VoucherOrder order = orders.lockById(found.get().getId()).orElseThrow();
            if (order.getStatus() != from) {
                return false;
            }
            order.changeStatus(to, Instant.now(clock));
            orders.save(order);
            if (auditAction != null) {
                audit.stripe(auditAction, null, order.getId(), Map.of("sessionId", sessionId));
            }
            return true;
        }));
    }

    /** {@code charge.refunded}. @return false, pokud objednávka není naše (ignorovat). */
    public boolean chargeRefunded(String account, String paymentIntentId, long amountRefundedMinor) {
        Optional<UUID> orderId = resolveOrder(account, paymentIntentId);
        if (orderId.isEmpty()) {
            return false;
        }
        tx.executeWithoutResult(s -> {
            VoucherOrder order = lockPaidOrder(orderId.get(), paymentIntentId);
            Instant now = Instant.now(clock);
            order.setRefundedAmountMinor(amountRefundedMinor);
            Optional<Voucher> voucher = vouchers.findByOrderId(order.getId());
            UUID voucherId = voucher.map(Voucher::getId).orElse(null);
            boolean full = amountRefundedMinor >= order.getAmountMinor();
            if (full) {
                if (voucher.isPresent() && voucher.get().getStatus() == VoucherStatus.ACTIVE) {
                    voucher.get().cancel(null, "REFUND", now);
                    vouchers.save(voucher.get());
                } else if (voucher.isPresent() && voucher.get().getStatus() == VoucherStatus.REDEEMED) {
                    order.flagAttention("Refund použitého voucheru – řešit individuálně.", now);
                }
                order.changeStatus(OrderStatus.REFUNDED, now);
                audit.stripe("ORDER_REFUNDED", voucherId, order.getId(), Map.of("refundedMinor", amountRefundedMinor));
            } else {
                order.flagAttention("Částečný refund (" + amountRefundedMinor / 100 + " Kč) – voucher beze změny, ověřte.", now);
                audit.stripe(
                        "ORDER_REFUNDED", voucherId, order.getId(),
                        Map.of("refundedMinor", amountRefundedMinor, "partial", true));
            }
            orders.save(order);
        });
        return true;
    }

    /** {@code charge.dispute.created}. */
    public boolean disputeCreated(String account, String paymentIntentId) {
        Optional<UUID> orderId = resolveOrder(account, paymentIntentId);
        if (orderId.isEmpty()) {
            return false;
        }
        tx.executeWithoutResult(s -> {
            VoucherOrder order = lockPaidOrder(orderId.get(), paymentIntentId);
            Instant now = Instant.now(clock);
            Optional<Voucher> voucher = vouchers.findByOrderId(order.getId());
            if (voucher.isPresent() && voucher.get().getStatus() == VoucherStatus.ACTIVE) {
                voucher.get().cancel(null, "DISPUTE", now);
                vouchers.save(voucher.get());
            }
            order.changeStatus(OrderStatus.DISPUTED, now);
            order.flagAttention("Spor (chargeback) – ověřte ve Stripe Dashboardu.", now);
            orders.save(order);
            audit.stripe("ORDER_DISPUTED", voucher.map(Voucher::getId).orElse(null), order.getId(), Map.of());
        });
        return true;
    }

    /** {@code charge.dispute.closed}: won → zpět PAID, lost → ponechat; vždy k řešení vedoucím. */
    public boolean disputeClosed(String account, String paymentIntentId, String disputeStatus) {
        Optional<UUID> orderId = resolveOrder(account, paymentIntentId);
        if (orderId.isEmpty()) {
            return false;
        }
        tx.executeWithoutResult(s -> {
            VoucherOrder order = lockPaidOrder(orderId.get(), paymentIntentId);
            Instant now = Instant.now(clock);
            if ("won".equals(disputeStatus)) {
                if (order.getStatus() == OrderStatus.DISPUTED) {
                    order.changeStatus(OrderStatus.PAID, now);
                }
                order.flagAttention("Spor vyhrán – rozhodněte, zda voucher reaktivovat.", now);
            } else {
                order.flagAttention("Spor uzavřen (" + disputeStatus + ") – peníze nemusí být vráceny, ověřte.", now);
            }
            orders.save(order);
            audit.stripe(
                    "ORDER_DISPUTE_CLOSED", vouchers.findByOrderId(order.getId()).map(Voucher::getId).orElse(null),
                    order.getId(), Map.of("result", String.valueOf(disputeStatus)));
        });
        return true;
    }

    /** Objednávka k PaymentIntentu – jen pokud vznikla na stejném Stripe účtu, ze kterého událost přišla. */
    private Optional<UUID> resolveOrder(String account, String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return Optional.empty();
        }
        Optional<VoucherOrder> byPi = orders.findByStripePaymentIntentId(paymentIntentId);
        if (byPi.isPresent()) {
            return byPi.filter(o -> Objects.equals(o.getStripeAccountId(), account)).map(VoucherOrder::getId);
        }
        // Fallback: PI ještě nebyl uložen k objednávce – dohledat přes metadata PaymentIntentu.
        return gateway.orderIdOfPaymentIntent(account, paymentIntentId)
                .map(FulfillmentService::parse)
                .flatMap(orders::findById)
                .filter(o -> Objects.equals(o.getStripeAccountId(), account))
                .map(VoucherOrder::getId);
    }

    private VoucherOrder lockPaidOrder(UUID orderId, String paymentIntentId) {
        VoucherOrder order = orders.lockById(orderId).orElseThrow();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT || order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
            // Objednávka ještě není vedená jako zaplacená – vyhodit výjimku, Stripe doručení zopakuje.
            throw new IllegalStateException("Order " + order.orderRef() + " not yet fulfilled; retry later");
        }
        if (order.getStripePaymentIntentId() == null) {
            order.setStripePaymentIntentId(paymentIntentId);
        }
        return order;
    }
}
