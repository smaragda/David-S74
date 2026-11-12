package cz.s74.vouchers.offer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class OfferController {

    private final OfferService offers;

    public OfferController(OfferService offers) {
        this.offers = offers;
    }

    @GetMapping("/offers")
    public OfferService.OffersResponse offers() {
        return offers.offers();
    }
}
