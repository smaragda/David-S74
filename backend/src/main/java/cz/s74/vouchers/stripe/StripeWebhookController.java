package cz.s74.vouchers.stripe;

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

    public StripeWebhookController(StripeWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/api/stripe/webhook")
    public ResponseEntity<Void> receive(
            @RequestBody byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        int status = webhook.handle(new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.status(status).build();
    }
}
