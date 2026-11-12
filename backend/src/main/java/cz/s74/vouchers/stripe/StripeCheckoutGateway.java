package cz.s74.vouchers.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StripeCheckoutGateway implements CheckoutGateway {

    /** Identifikátor integrace (analýza kap. 10.2) – vygenerováno jednou, konstantní. */
    static final String INTEGRATION_IDENTIFIER = "s74_vouchers_qkwmzvtr";

    private final StripeClient client;

    public StripeCheckoutGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public CreatedSession createSession(SessionRequest r) {
        SessionCreateParams params = buildParams(r);
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-session-" + r.orderId())
                .build();
        try {
            Session session = client.checkout().sessions().create(params, options);
            return new CreatedSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe checkout session creation failed: " + e.getCode(), e);
        }
    }

    /**
     * Parametry Checkout Session. Záměrně bez {@code payment_method_types} (metody řídí Dashboard),
     * bez {@code automatic_tax} a bez zakládání Customer / Product / Price objektů.
     */
    static SessionCreateParams buildParams(SessionRequest r) {
        return SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(r.currency().toLowerCase())
                                .setUnitAmount(r.amountMinor())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(r.productName())
                                        .setDescription(r.productDescription())
                                        .build())
                                .build())
                        .build())
                .setCustomerEmail(r.customerEmail())
                .setClientReferenceId(r.orderId().toString())
                .putMetadata("order_id", r.orderId().toString())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata("order_id", r.orderId().toString())
                        .setDescription("Voucher S74 " + r.orderRef())
                        .build())
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl())
                .setExpiresAt(r.expiresAt().getEpochSecond())
                .setLocale(SessionCreateParams.Locale.CS)
                .setSubmitType(SessionCreateParams.SubmitType.PAY)
                .setCustomText(SessionCreateParams.CustomText.builder()
                        .setSubmit(SessionCreateParams.CustomText.Submit.builder()
                                .setMessage("Voucher je jednorázový. Nevyužitý rozdíl hodnoty propadá.")
                                .build())
                        .build())
                .setIntegrationIdentifier(INTEGRATION_IDENTIFIER)
                .build();
    }

    @Override
    public Optional<SessionSnapshot> retrieveSession(String sessionId) {
        try {
            Session s = client.checkout().sessions().retrieve(sessionId);
            return Optional.of(new SessionSnapshot(
                    s.getId(),
                    s.getMode(),
                    s.getCurrency(),
                    s.getAmountTotal(),
                    s.getPaymentStatus(),
                    s.getStatus(),
                    s.getPaymentIntent(),
                    s.getMetadata() == null ? null : s.getMetadata().get("order_id")));
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return Optional.empty();
            }
            throw new PaymentProviderException("Stripe session retrieval failed: " + e.getCode(), e);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe session retrieval failed: " + e.getCode(), e);
        }
    }

    @Override
    public Optional<String> orderIdOfPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent pi = client.paymentIntents().retrieve(paymentIntentId);
            return Optional.ofNullable(pi.getMetadata()).map(m -> m.get("order_id"));
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode())) {
                return Optional.empty();
            }
            throw new PaymentProviderException("Stripe payment intent retrieval failed: " + e.getCode(), e);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe payment intent retrieval failed: " + e.getCode(), e);
        }
    }
}
