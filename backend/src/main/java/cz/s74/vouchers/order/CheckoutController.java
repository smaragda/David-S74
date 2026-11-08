package cz.s74.vouchers.order;

import jakarta.servlet.http.HttpServletRequest;
import cz.s74.vouchers.tenant.PublicTenantResolver;
import cz.s74.vouchers.tenant.TenantSettings;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class CheckoutController {

    private final CheckoutService checkout;
    private final PublicTenantResolver tenants;

    public CheckoutController(CheckoutService checkout, PublicTenantResolver tenants) {
        this.checkout = checkout;
        this.tenants = tenants;
    }

    public record CheckoutRequest(String offerId, String email, Boolean termsAccepted, String termsVersion) {}

    @PostMapping("/checkout-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody CheckoutRequest request, HttpServletRequest http) {
        return create(tenants.defaultTenant(), request, http);
    }

    @PostMapping("/t/{slug}/checkout-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(
            @PathVariable("slug") String slug, @RequestBody CheckoutRequest request, HttpServletRequest http) {
        return create(tenants.bySlug(slug), request, http);
    }

    private Map<String, String> create(TenantSettings tenant, CheckoutRequest request, HttpServletRequest http) {
        String url = checkout.createCheckout(
                tenant,
                new CheckoutService.CheckoutCommand(
                        request.offerId(), request.email(), request.termsAccepted(), request.termsVersion(),
                        CheckoutService.ReturnTo.SITE),
                http.getRemoteAddr());
        return Map.of("checkoutUrl", url);
    }
}
