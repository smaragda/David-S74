package cz.s74.vouchers.stripe;

import cz.s74.vouchers.connect.AccountEventsWebhookService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Bez CORS, bez CSRF, bez session – autenticitu zajišťuje podpis. Tělo se čte surové (žádný filtr ho nemění). */
@RestController
public class StripeWebhookController {

    private final StripeWebhookService webhook;
    private final AccountEventsWebhookService accountEvents;

    public StripeWebhookController(StripeWebhookService webhook, AccountEventsWebhookService accountEvents) {
        this.webhook = webhook;
        this.accountEvents = accountEvents;
    }

    /** Události vlastního účtu platformy (výchozí podnik S74 v režimu PLATFORM). */
    @PostMapping("/api/stripe/webhook")
    public ResponseEntity<Void> receive(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        int status = webhook.handle(
                StripeWebhookService.Endpoint.PLATFORM, new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.status(status).build();
    }

    /** Události připojených účtů podniků (Connect webhook endpoint, „Events on connected accounts“). */
    @PostMapping("/api/stripe/connect-webhook")
    public ResponseEntity<Void> receiveConnect(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        int status = webhook.handle(
                StripeWebhookService.Endpoint.CONNECT, new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.status(status).build();
    }

    /** v2 (thin) události účtů – změny capability a požadavků Accounts v2. */
    @PostMapping("/api/stripe/account-events")
    public ResponseEntity<Void> receiveAccountEvents(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        int status = accountEvents.handle(new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.status(status).build();
    }
}
