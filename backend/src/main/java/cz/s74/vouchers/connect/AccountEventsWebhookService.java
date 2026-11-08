package cz.s74.vouchers.connect;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.stripe.StripeEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * v2 (thin) události Accounts v2, např. {@code v2.core.account[configuration.merchant].capability_status_updated}
 * nebo {@code v2.core.account[requirements].updated}. Thin událost nese jen odkaz na objekt – stav účtu se proto
 * vždy dočte z API ({@link ConnectService#refreshByAccount}). Podpis má stejné schéma jako u v1 webhooků.
 */
@Service
public class AccountEventsWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AccountEventsWebhookService.class);
    private static final long TOLERANCE_SECONDS = 300;

    private final S74Properties props;
    private final StripeEventStore events;
    private final ConnectService connect;
    private final JsonMapper json;

    public AccountEventsWebhookService(
            S74Properties props, StripeEventStore events, ConnectService connect, JsonMapper json) {
        this.props = props;
        this.events = events;
        this.connect = connect;
        this.json = json;
    }

    public int handle(String payload, String signatureHeader) {
        String secret = props.stripe().accountEventsWebhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Stripe account events received but STRIPE_ACCOUNT_EVENTS_WEBHOOK_SECRET is not configured");
            return 404;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return 400;
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, secret, TOLERANCE_SECONDS);
        } catch (SignatureVerificationException e) {
            log.warn("WEBHOOK_SIGNATURE_INVALID (account events)");
            return 400;
        }
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException e) {
            return 400;
        }
        String id = text(root, "id");
        String type = text(root, "type");
        if (id == null || type == null) {
            return 400;
        }
        boolean livemode = root.path("livemode").asBoolean(false);
        String accountId = text(root.path("related_object"), "id");
        if (accountId == null || !accountId.startsWith("acct_")) {
            accountId = text(root, "context");
        }
        boolean first = events.tryInsert(id, type, livemode, accountId);
        if (!first) {
            var existing = events.status(id);
            if (existing.isPresent() && existing.get() != StripeEventStore.Status.FAILED
                    && existing.get() != StripeEventStore.Status.RECEIVED) {
                return 200;
            }
        }
        if (livemode != props.stripe().expectedLivemode()) {
            events.mark(id, StripeEventStore.Status.IGNORED, "livemode mismatch");
            return 200;
        }
        try {
            boolean handled = type.startsWith("v2.core.account")
                    && accountId != null
                    && accountId.startsWith("acct_")
                    && connect.refreshByAccount(accountId);
            events.mark(id, handled ? StripeEventStore.Status.PROCESSED : StripeEventStore.Status.IGNORED, null);
            return 200;
        } catch (RuntimeException e) {
            events.mark(id, StripeEventStore.Status.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Processing of Stripe account event {} ({}) failed", id, type, e);
            return 500;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node == null ? null : node.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }
}
