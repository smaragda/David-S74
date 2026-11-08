package cz.s74.vouchers.order;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.ApiException;
import cz.s74.vouchers.common.EmailAddresses;
import cz.s74.vouchers.common.Money;
import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.tenant.TenantSettings;
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
    private final OrderRepository orders;
    private final CheckoutGateway gateway;
    private final AuditService audit;
    private final RateLimits limits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(
            S74Properties props,
            OrderRepository orders,
            CheckoutGateway gateway,
            AuditService audit,
            RateLimits limits,
            TransactionTemplate tx,
            Clock clock) {
        this.props = props;
        this.orders = orders;
        this.gateway = gateway;
        this.audit = audit;
        this.limits = limits;
        this.tx = tx;
        this.clock = clock;
    }

    /** Kam se zákazník po platbě vrátí: na web podniku (vložitelný skript) nebo na hostovanou stránku platformy. */
    public enum ReturnTo {
        SITE,
        HOSTED
    }

    public record CheckoutCommand(
            String offerId, String email, Boolean termsAccepted, String termsVersion, ReturnTo returnTo) {}

    public String createCheckout(TenantSettings tenant, CheckoutCommand cmd, String clientIp) {
        if (!limits.checkoutPerIp.tryAcquire(clientIp)) {
            audit.record(tenant.id(), AuditService.Actor.SYSTEM, null, "RATE_LIMITED", null, null, clientIp,
                    Map.of("scope", "checkout"));
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Příliš mnoho požadavků. Zkuste to prosím později.");
        }

        if (!tenant.salesEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "SALES_DISABLED", "Prodej voucherů je teď pozastaven. Zkuste to prosím později.");
        }
        TenantSettings.Offer offer = tenant.offer(cmd.offerId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST, "INVALID_OFFER", "Vybraná hodnota voucheru není dostupná."));
        String email = normalizeEmail(cmd.email());
        if (!Boolean.TRUE.equals(cmd.termsAccepted())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TERMS_NOT_ACCEPTED",
                    "Pro pokračování je nutný souhlas s obchodními podmínkami.");
        }
        if (!tenant.termsVersion().equals(cmd.termsVersion())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TERMS_OUTDATED",
                    "Obchodní podmínky se mezitím změnily. Načtěte prosím stránku znovu.");
        }

        Instant now = Instant.now(clock);
        VoucherOrder order = tx.execute(status -> {
            VoucherOrder o = orders.saveAndFlush(new VoucherOrder(
                    UUID.randomUUID(),
                    tenant.id(),
                    tenant.stripeAccountId(),
                    tenant.applicationFeeFor(offer.amountMinor()),
                    offer.id(),
                    offer.amountMinor(),
                    email,
                    tenant.termsVersion(),
                    now));
            audit.system("ORDER_CREATED", null, o.getId(), Map.of("offerId", offer.id()));
            return o;
        });

        boolean hosted = cmd.returnTo() == ReturnTo.HOSTED || tenant.frontendBaseUrl() == null;
        String successUrl = hosted
                ? tenant.hostedUrl() + "/hotovo?session_id={CHECKOUT_SESSION_ID}"
                : tenant.frontendBaseUrl() + "/order-success.html?session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = hosted
                ? tenant.hostedUrl() + "?offer=" + offer.id() + "&cancelled=1"
                : tenant.frontendBaseUrl() + "/checkout.html?offer=" + offer.id() + "&cancelled=1";
        // Stripe vyžaduje expiraci minimálně 30 min od vytvoření; minuta rezervy kryje latenci požadavku.
        Instant expiresAt = now.plus(Duration.ofMinutes(props.stripe().checkoutExpiryMinutes() + 1L));
        CheckoutGateway.CreatedSession session;
        try {
            session = gateway.createSession(new CheckoutGateway.SessionRequest(
                    order.getId(),
                    order.orderRef(),
                    tenant.id(),
                    order.getStripeAccountId(),
                    order.getApplicationFeeMinor(),
                    offer.id(),
                    order.getAmountMinor(),
                    order.getCurrency(),
                    email,
                    "Dárkový voucher " + tenant.displayName() + " – " + Money.czk(offer.amountCzk()),
                    "Jednorázový voucher, platnost " + tenant.validityMonths() + " měsíců od vystavení.",
                    "Voucher " + tenant.displayName() + " " + order.orderRef(),
                    successUrl,
                    cancelUrl,
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
