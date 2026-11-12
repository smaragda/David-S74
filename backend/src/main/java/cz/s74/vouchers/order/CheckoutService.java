package cz.s74.vouchers.order;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.ApiException;
import cz.s74.vouchers.common.EmailAddresses;
import cz.s74.vouchers.common.Money;
import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.offer.OfferService;
import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.stripe.PaymentProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final S74Properties props;
    private final OfferService offers;
    private final OrderRepository orders;
    private final CheckoutGateway gateway;
    private final AuditService audit;
    private final RateLimits limits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(
            S74Properties props,
            OfferService offers,
            OrderRepository orders,
            CheckoutGateway gateway,
            AuditService audit,
            RateLimits limits,
            TransactionTemplate tx,
            Clock clock) {
        this.props = props;
        this.offers = offers;
        this.orders = orders;
        this.gateway = gateway;
        this.audit = audit;
        this.limits = limits;
        this.tx = tx;
        this.clock = clock;
    }

    public record CheckoutCommand(String offerId, String email, Boolean termsAccepted, String termsVersion) {}

    public String createCheckout(CheckoutCommand cmd, String clientIp) {
        if (!limits.checkoutPerIp.tryAcquire(clientIp)) {
            audit.system("RATE_LIMITED", null, null, Map.of("scope", "checkout"));
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Příliš mnoho požadavků. Zkuste to prosím později.");
        }

        S74Properties.Offer offer = offers.find(cmd.offerId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, "INVALID_OFFER", "Vybraná hodnota voucheru není dostupná."));
        String email = normalizeEmail(cmd.email());
        if (!Boolean.TRUE.equals(cmd.termsAccepted())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TERMS_NOT_ACCEPTED",
                    "Pro pokračování je nutný souhlas s obchodními podmínkami.");
        }
        if (!props.voucher().termsVersion().equals(cmd.termsVersion())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TERMS_OUTDATED",
                    "Obchodní podmínky se mezitím změnily. Načtěte prosím stránku znovu.");
        }

        Instant now = Instant.now(clock);
        VoucherOrder order = tx.execute(status -> {
            VoucherOrder o = orders.saveAndFlush(new VoucherOrder(
                    UUID.randomUUID(),
                    offer.id(),
                    offer.amountMinor(),
                    email,
                    props.voucher().termsVersion(),
                    now));
            audit.system("ORDER_CREATED", null, o.getId(), Map.of("offerId", offer.id()));
            return o;
        });

        String frontend = props.frontendBaseUrlNoSlash();
        // Stripe vyžaduje expiraci minimálně 30 min od vytvoření; minuta rezervy kryje latenci požadavku.
        Instant expiresAt = now.plus(Duration.ofMinutes(props.stripe().checkoutExpiryMinutes() + 1L));
        CheckoutGateway.CreatedSession session;
        try {
            session = gateway.createSession(new CheckoutGateway.SessionRequest(
                    order.getId(),
                    order.orderRef(),
                    offer.id(),
                    order.getAmountMinor(),
                    order.getCurrency(),
                    email,
                    "Dárkový voucher S74 wine&café – " + Money.czk(offer.amountCzk()),
                    "Jednorázový voucher, platnost " + props.voucher().validityMonths() + " měsíců od vystavení.",
                    frontend + "/order-success.html?session_id={CHECKOUT_SESSION_ID}",
                    frontend + "/checkout.html?offer=" + offer.id() + "&cancelled=1",
                    expiresAt));
        } catch (PaymentProviderException e) {
            log.error("Payment provider unavailable for order {}: {}", order.orderRef(), e.getMessage());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PAYMENT_PROVIDER_UNAVAILABLE",
                    "Platební brána je teď nedostupná. Zkuste to prosím za chvíli.");
        }

        tx.executeWithoutResult(status -> {
            VoucherOrder o = orders.findById(order.getId()).orElseThrow();
            o.setStripeCheckoutSessionId(session.id());
            orders.save(o);
        });
        return session.url();
    }

    static String normalizeEmail(String raw) {
        return EmailAddresses.normalize(raw)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Zadejte platnou e-mailovou adresu."));
    }
}
