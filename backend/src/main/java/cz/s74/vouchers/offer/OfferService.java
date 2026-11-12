package cz.s74.vouchers.offer;

import cz.s74.vouchers.common.Money;
import cz.s74.vouchers.config.S74Properties;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Ceník z konfigurace – cena se bere výhradně odtud, nikdy z požadavku. */
@Service
public class OfferService {

    private final S74Properties props;

    public OfferService(S74Properties props) {
        this.props = props;
    }

    public Optional<S74Properties.Offer> find(String offerId) {
        if (offerId == null) {
            return Optional.empty();
        }
        return props.voucher().offers().stream().filter(o -> o.id().equals(offerId)).findFirst();
    }

    public OffersResponse offers() {
        List<OfferDto> offers = props.voucher().offers().stream()
                .map(o -> new OfferDto(o.id(), o.amountCzk(), label(o)))
                .toList();
        return new OffersResponse(
                "CZK",
                props.voucher().validityMonths(),
                props.voucher().termsVersion(),
                props.frontendBaseUrlNoSlash() + props.voucher().termsPath(),
                !props.stripe().expectedLivemode(),
                offers);
    }

    public static String label(S74Properties.Offer o) {
        return "Voucher v hodnotě " + Money.czk(o.amountCzk());
    }

    /** {@code testMode}: platby jdou přes Stripe test/sandbox (STRIPE_LIVEMODE=false); web podle toho zobrazí upozornění. */
    public record OffersResponse(
            String currency,
            int validityMonths,
            String termsVersion,
            String termsUrl,
            boolean testMode,
            List<OfferDto> offers) {}

    public record OfferDto(String id, long amountCzk, String label) {}
}
