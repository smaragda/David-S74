package cz.s74.vouchers.stripe;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Hranice vůči Stripe API – v testech nahrazena atrapou. */
public interface CheckoutGateway {

    CreatedSession createSession(SessionRequest request);

    /** Aktuální stav Checkout Session ze Stripe (ne z payloadu webhooku). Prázdné, pokud neexistuje. */
    Optional<SessionSnapshot> retrieveSession(String sessionId);

    /** {@code metadata.order_id} PaymentIntentu (fallback pro refund / spor). */
    Optional<String> orderIdOfPaymentIntent(String paymentIntentId);

    record SessionRequest(
            UUID orderId,
            String orderRef,
            String offerId,
            long amountMinor,
            String currency,
            String customerEmail,
            String productName,
            String productDescription,
            String successUrl,
            String cancelUrl,
            Instant expiresAt) {}

    record CreatedSession(String id, String url) {}

    record SessionSnapshot(
            String id,
            String mode,
            String currency,
            Long amountTotal,
            String paymentStatus,
            String status,
            String paymentIntentId,
            String orderId) {}
}
