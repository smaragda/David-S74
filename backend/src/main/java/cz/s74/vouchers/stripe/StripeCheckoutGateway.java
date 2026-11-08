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
        RequestOptions options = options(r.stripeAccountId())
                .setIdempotencyKey("checkout-session-" + r.orderId())
                .build();
        try {
            Session session = client.checkout().sessions().create(params, options);
            return new CreatedSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe checkout session creation failed: " + e.getCode(), e);
        }
    }

    /** Volání za připojený účet podniku (direct charge) nese hlavičku {@code Stripe-Account}. */
    static RequestOptions.RequestOptionsBuilder options(String stripeAccountId) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder();
        if (stripeAccountId != null) {
            builder.setStripeAccount(stripeAccountId);
        }
        return builder;
    }

    /**
     * Parametry Checkout Session. Záměrně bez {@code payment_method_types} (metody řídí Dashboard),
     * bez {@code automatic_tax} a bez zakládání Customer / Product / Price objektů.
     * U přímé platby na připojený účet se případná provize platformy předává jako {@code application_fee_amount}.
     */
    static SessionCreateParams buildParams(SessionRequest r) {
        SessionCreateParams.PaymentIntentData.Builder paymentIntent = SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("order_id", r.orderId().toString())
                .putMetadata("tenant_id", r.tenantId().toString())
                .setDescription(r.paymentDescription());
        if (r.stripeAccountId() != null && r.applicationFeeMinor() > 0) {
            paymentIntent.setApplicationFeeAmount(r.applicationFeeMinor());
        }
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
                .putMetadata("tenant_id", r.tenantId().toString())
                .setPaymentIntentData(paymentIntent.build())
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
    public Optional<SessionSnapshot> retrieveSession(String stripeAccountId, String sessionId) {
        try {
            Session s = client.checkout().sessions().retrieve(
                    sessionId, com.stripe.param.checkout.SessionRetrieveParams.builder().build(), options(stripeAccountId).build());
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
    public Optional<String> orderIdOfPaymentIntent(String stripeAccountId, String paymentIntentId) {
        try {
            PaymentIntent pi = client.paymentIntents().retrieve(
                    paymentIntentId, com.stripe.param.PaymentIntentRetrieveParams.builder().build(), options(stripeAccountId).build());
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
