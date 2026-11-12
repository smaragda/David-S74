package cz.s74.vouchers.email;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox odesílání: webhook ani jiný požadavek e-mail neposílá přímo, jen založí řádek {@code email_delivery}.
 * Odesílání, opakování s backoffem a přechod do FAILED řeší tato třída.
 */
@Service
public class EmailOutbox {

    private static final Logger log = LoggerFactory.getLogger(EmailOutbox.class);
    /** Prodlevy po 1., 2., 3. a 4. neúspěšném pokusu; 5. neúspěch = FAILED. */
    static final List<Duration> BACKOFF =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    private static final int BATCH = 10;

    private final EmailDeliveryRepository deliveries;
    private final VoucherRepository vouchers;
    private final OrderRepository orders;
    private final VoucherMailer mailer;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final S74Properties props;

    public EmailOutbox(
            EmailDeliveryRepository deliveries,
            VoucherRepository vouchers,
            OrderRepository orders,
            VoucherMailer mailer,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock,
            S74Properties props) {
        this.deliveries = deliveries;
        this.vouchers = vouchers;
        this.orders = orders;
        this.mailer = mailer;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    /** Založí opětovné zaslání (nový řádek, {@code kind = RESEND}); odešle ho job. */
    public UUID queueResend(UUID voucherId, String recipient, UUID requestedBy) {
        return tx.execute(s -> deliveries
                .save(new EmailDelivery(
                        UUID.randomUUID(),
                        voucherId,
                        EmailDelivery.Kind.RESEND,
                        recipient,
                        requestedBy,
                        Instant.now(clock)))
                .getId());
    }

    @Scheduled(fixedDelay = 30_000)
    public void processDue() {
        List<UUID> claimed = tx.execute(s -> {
            Instant now = Instant.now(clock);
            deliveries.resetStuck(now.minus(STUCK_AFTER));
            List<EmailDelivery> due = deliveries.lockDue(now, BATCH);
            due.forEach(d -> d.markSending(now));
            deliveries.saveAll(due);
            return due.stream().map(EmailDelivery::getId).toList();
        });
        for (UUID id : claimed) {
            deliver(id);
        }
    }

    private void deliver(UUID id) {
        EmailDelivery delivery = deliveries.findById(id).orElseThrow();
        Voucher voucher = vouchers.findById(delivery.getVoucherId()).orElseThrow();
        VoucherOrder order = orders.findById(voucher.getOrderId()).orElseThrow();
        try {
            String messageId = mailer.send(voucher, order, delivery.getRecipient());
            tx.executeWithoutResult(s -> {
                EmailDelivery d = deliveries.findById(id).orElseThrow();
                d.markSent(messageId, Instant.now(clock));
                deliveries.save(d);
                audit.system("EMAIL_SENT", voucher.getId(), order.getId(), Map.of("kind", d.getKind().name()));
            });
            log.info("Voucher e-mail sent for order {}", order.orderRef());
        } catch (Exception e) {
            handleFailure(id, voucher, order, e);
        }
    }

    private void handleFailure(UUID id, Voucher voucher, VoucherOrder order, Exception e) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        log.warn("Sending voucher e-mail for order {} failed: {}", order.orderRef(), e.getClass().getSimpleName());
        tx.executeWithoutResult(s -> {
            EmailDelivery d = deliveries.findById(id).orElseThrow();
            Instant now = Instant.now(clock);
            int attemptsDone = d.getAttempts() + 1;
            if (attemptsDone >= props.mail().maxAttempts()) {
                d.markFailed(error);
                VoucherOrder o = orders.findById(order.getId()).orElseThrow();
                o.flagAttention("E-mail s voucherem se nepodařilo odeslat – zkontrolujte adresu a pošlete znovu.", now);
                orders.save(o);
                audit.system("EMAIL_FAILED", voucher.getId(), order.getId(), Map.of("attempts", attemptsDone));
            } else {
                Duration delay = BACKOFF.get(Math.min(attemptsDone - 1, BACKOFF.size() - 1));
                d.markRetry(error, now.plus(delay));
            }
            deliveries.save(d);
        });
    }
}
