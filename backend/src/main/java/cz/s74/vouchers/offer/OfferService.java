package cz.s74.vouchers.offer;

import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.tenant.TenantSettings;
import java.util.List;
import org.springframework.stereotype.Service;

/** Ceník podniku – cena se bere výhradně odtud, nikdy z požadavku. */
@Service
public class OfferService {

    private final S74Properties props;

    public OfferService(S74Properties props) {
        this.props = props;
    }

    public OffersResponse offers(TenantSettings tenant) {
        List<OfferDto> offers = tenant.offers().stream()
                .map(o -> new OfferDto(o.id(), o.amountCzk(), o.label()))
                .toList();
        return new OffersResponse(
                "CZK",
                tenant.validityMonths(),
                tenant.termsVersion(),
                tenant.termsUrl(),
                !props.stripe().expectedLivemode(),
                tenant.salesEnabled(),
                new TenantDto(tenant.slug(), tenant.displayName()),
                offers);
    }

    /**
     * {@code testMode}: platby jdou přes Stripe test/sandbox (STRIPE_LIVEMODE=false); web podle toho zobrazí upozornění.
     * {@code salesEnabled}: podnik smí prodávat (aktivní a s připraveným platebním účtem).
     */
    public record OffersResponse(
            String currency,
            int validityMonths,
            String termsVersion,
            String termsUrl,
            boolean testMode,
            boolean salesEnabled,
            TenantDto tenant,
            List<OfferDto> offers) {}

    public record TenantDto(String slug, String name) {}

    public record OfferDto(String id, long amountCzk, String label) {}
}
