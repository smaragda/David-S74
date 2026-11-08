package cz.s74.vouchers.offer;

import cz.s74.vouchers.tenant.PublicTenantResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class OfferController {

    private final OfferService offers;
    private final PublicTenantResolver tenants;

    public OfferController(OfferService offers, PublicTenantResolver tenants) {
        this.offers = offers;
        this.tenants = tenants;
    }

    /** Výchozí podnik (S74) – původní adresa, kterou používá statický web s74.cz. */
    @GetMapping("/offers")
    public OfferService.OffersResponse offers() {
        return offers.offers(tenants.defaultTenant());
    }

    @GetMapping("/t/{slug}/offers")
    public OfferService.OffersResponse offers(@PathVariable("slug") String slug) {
        return offers.offers(tenants.bySlug(slug));
    }
}
