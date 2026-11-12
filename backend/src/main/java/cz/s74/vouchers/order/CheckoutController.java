package cz.s74.vouchers.order;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class CheckoutController {

    private final CheckoutService checkout;

    public CheckoutController(CheckoutService checkout) {
        this.checkout = checkout;
    }

    public record CheckoutRequest(String offerId, String email, Boolean termsAccepted, String termsVersion) {}

    @PostMapping("/checkout-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody CheckoutRequest request, HttpServletRequest http) {
        String url = checkout.createCheckout(
                new CheckoutService.CheckoutCommand(
                        request.offerId(), request.email(), request.termsAccepted(), request.termsVersion()),
                http.getRemoteAddr());
        return Map.of("checkoutUrl", url);
    }
}
