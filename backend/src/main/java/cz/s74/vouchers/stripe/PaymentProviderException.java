package cz.s74.vouchers.stripe;

/** Stripe je nedostupný nebo vrátil chybu; zprávy nesmí obsahovat klíče. */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
