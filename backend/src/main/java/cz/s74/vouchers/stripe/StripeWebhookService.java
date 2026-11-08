package cz.s74.vouchers.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.connect.ConnectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Zpracování webhooku: podpis → livemode → idempotence ({@code stripe_event}) → dispatch.
 * Z těla události se čte jen surové JSON data objektu (několik polí), takže zpracování nezávisí na shodě
 * API verze endpointu a SDK; skutečný stav platby se vždy dočte z API ({@link FulfillmentService}).
 *
 * <p>Dva endpointy se stejnou logikou: platformní (události vlastního účtu platformy, {@code account} = null)
 * a Connect (události připojených účtů podniků, {@code account} = {@code acct_…}). Účet z události určuje,
 * ze kterého účtu se session čte a které objednávce smí patřit.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final long TOLERANCE_SECONDS = 300;

    public enum Endpoint {
        PLATFORM,
        CONNECT
    }

    private final S74Properties props;
    private final StripeEventStore events;
    private final FulfillmentService fulfillment;
    private final PaymentEventService payments;
    private final ConnectService connect;
    private final JsonMapper json;

    public StripeWebhookService(
            S74Properties props,
            StripeEventStore events,
            FulfillmentService fulfillment,
            PaymentEventService payments,
            ConnectService connect,
            JsonMapper json) {
        this.props = props;
        this.events = events;
        this.fulfillment = fulfillment;
        this.payments = payments;
        this.connect = connect;
        this.json = json;
    }

    /** @return HTTP status odpovědi pro Stripe. */
    public int handle(String payload, String signatureHeader) {
        return handle(Endpoint.PLATFORM, payload, signatureHeader);
    }

    public int handle(Endpoint endpoint, String payload, String signatureHeader) {
        String secret = endpoint == Endpoint.CONNECT
                ? props.stripe().connectWebhookSecret()
                : props.stripe().webhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Stripe {} webhook received but its secret is not configured", endpoint);
            return 404;
        }
        Event event;
        try {
            if (signatureHeader == null || signatureHeader.isBlank()) {
                log.warn("WEBHOOK_SIGNATURE_INVALID: missing Stripe-Signature header");
                return 400;
            }
            event = Webhook.constructEvent(payload, signatureHeader, secret, TOLERANCE_SECONDS);
        } catch (SignatureVerificationException e) {
            log.warn("WEBHOOK_SIGNATURE_INVALID ({})", endpoint);
            return 400;
        } catch (RuntimeException e) {
            log.warn("WEBHOOK_PAYLOAD_INVALID: {}", e.getClass().getSimpleName());
            return 400;
        }

        String account = blankToNull(event.getAccount());
        boolean livemode = Boolean.TRUE.equals(event.getLivemode());
        boolean first = events.tryInsert(event.getId(), event.getType(), livemode, account);
        if (!first) {
            var existing = events.status(event.getId());
            if (existing.isPresent()
                    && (existing.get() == StripeEventStore.Status.PROCESSED
                            || existing.get() == StripeEventStore.Status.IGNORED)) {
                log.info("Duplicate Stripe event {} ({}) ignored", event.getId(), event.getType());
                return 200;
            }
            // RECEIVED / FAILED: předchozí zpracování nedoběhlo, zpracujeme znovu (vše je idempotentní).
        }
        if (livemode != props.stripe().expectedLivemode()) {
            events.mark(event.getId(), StripeEventStore.Status.IGNORED, "livemode mismatch");
            log.warn("Stripe event {} ignored: livemode={} but expected {}", event.getId(), livemode,
                    props.stripe().expectedLivemode());
            return 200;
        }
        if (endpoint == Endpoint.CONNECT && account == null) {
            events.mark(event.getId(), StripeEventStore.Status.IGNORED, "connect endpoint without account");
            return 200;
        }

        try {
            boolean handled = dispatch(event, account);
            events.mark(
                    event.getId(),
                    handled ? StripeEventStore.Status.PROCESSED : StripeEventStore.Status.IGNORED,
                    null);
            return 200;
        } catch (Exception e) {
            events.mark(event.getId(), StripeEventStore.Status.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Processing of Stripe event {} ({}) failed", event.getId(), event.getType(), e);
            return 500;
        }
    }

    private boolean dispatch(Event event, String account) {
        JsonNode obj = dataObject(event);
        return switch (event.getType()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                fulfillment.fulfill(account, string(obj, "id")) != FulfillmentService.Outcome.IGNORED;
            case "checkout.session.async_payment_failed" -> payments.asyncPaymentFailed(account, string(obj, "id"));
            case "checkout.session.expired" -> payments.sessionExpired(account, string(obj, "id"));
            case "charge.refunded" ->
                payments.chargeRefunded(account, string(obj, "payment_intent"), number(obj, "amount_refunded"));
            case "charge.dispute.created" -> payments.disputeCreated(account, string(obj, "payment_intent"));
            case "charge.dispute.closed" ->
                payments.disputeClosed(account, string(obj, "payment_intent"), string(obj, "status"));
            case "account.updated" -> account != null && connect.refreshByAccount(account);
            case "account.application.deauthorized" -> account != null && connect.deauthorized(account);
            default -> false;
        };
    }

    private JsonNode dataObject(Event event) {
        try {
            return json.readTree(event.getDataObjectDeserializer().getRawJson());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Event data object is not valid JSON");
        }
    }

    private static String string(JsonNode o, String field) {
        JsonNode n = o.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    private static long number(JsonNode o, String field) {
        JsonNode n = o.get(field);
        return n == null || n.isNull() ? 0L : n.asLong();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
