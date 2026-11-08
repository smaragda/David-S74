package cz.s74.vouchers.hosted;

import cz.s74.vouchers.common.ApiException;
import cz.s74.vouchers.common.Formats;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.order.CheckoutService;
import cz.s74.vouchers.order.OrderStatusService;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantService;
import cz.s74.vouchers.tenant.TenantSettings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hostované prodejní stránky podniku na doméně platformy ({@code /v/{slug}}) – pro podniky bez vlastního webu.
 * Bez JavaScriptu a bez cookies: formulář → Stripe Checkout → stránka s výsledkem (obnovuje se meta refresh).
 * Logika je stejná jako u veřejného API (ceník, validace, rate limit, vystavení voucheru).
 */
@Controller
public class HostedShopController {

    private final TenantService tenants;
    private final CheckoutService checkout;
    private final OrderStatusService status;
    private final S74Properties props;
    private final Formats formats;

    public HostedShopController(
            TenantService tenants,
            CheckoutService checkout,
            OrderStatusService status,
            S74Properties props,
            Formats formats) {
        this.tenants = tenants;
        this.checkout = checkout;
        this.status = status;
        this.props = props;
        this.formats = formats;
    }

    @GetMapping("/v/{slug}")
    public String shop(
            @PathVariable("slug") String slug,
            @RequestParam(value = "offer", required = false) String offer,
            @RequestParam(value = "cancelled", required = false) String cancelled,
            Model model) {
        TenantSettings tenant = tenant(slug);
        fill(model, tenant);
        model.addAttribute("selectedOffer", offer != null ? offer : firstOffer(tenant));
        model.addAttribute("email", "");
        model.addAttribute("cancelled", cancelled != null);
        return "hosted/shop";
    }

    @PostMapping("/v/{slug}/checkout")
    public String buy(
            @PathVariable("slug") String slug,
            @RequestParam(value = "offerId", required = false) String offerId,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "emailConfirm", required = false) String emailConfirm,
            @RequestParam(value = "termsAccepted", required = false) String termsAccepted,
            @RequestParam(value = "termsVersion", required = false) String termsVersion,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {
        TenantSettings tenant = tenant(slug);
        String error = null;
        if (email == null || emailConfirm == null || !email.trim().equalsIgnoreCase(emailConfirm.trim())) {
            error = "E-mailové adresy se neshodují.";
        } else {
            try {
                String url = checkout.createCheckout(
                        tenant,
                        new CheckoutService.CheckoutCommand(
                                offerId, email, "on".equals(termsAccepted) || "true".equals(termsAccepted), termsVersion,
                                CheckoutService.ReturnTo.HOSTED),
                        request.getRemoteAddr());
                response.setStatus(HttpStatus.SEE_OTHER.value());
                response.setHeader("Location", url);
                return null;
            } catch (ApiException e) {
                error = e.getMessage();
                response.setStatus(e.status().value());
            }
        }
        if (response.getStatus() < 400) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
        fill(model, tenants.settings(tenants.bySlug(slug).orElseThrow()));
        model.addAttribute("selectedOffer", offerId);
        model.addAttribute("email", email == null ? "" : email.trim());
        model.addAttribute("cancelled", false);
        model.addAttribute("error", error);
        return "hosted/shop";
    }

    @GetMapping("/v/{slug}/hotovo")
    public String result(
            @PathVariable("slug") String slug,
            @RequestParam(value = "session_id", required = false) String sessionId,
            HttpServletRequest request,
            Model model) {
        TenantSettings tenant = tenant(slug);
        fill(model, tenant);
        OrderStatusService.StatusResponse result;
        try {
            result = status.status(tenant, sessionId, request.getRemoteAddr());
        } catch (ApiException e) {
            result = new OrderStatusService.StatusResponse("RATE_LIMITED", null, null, null, null, null);
        }
        model.addAttribute("result", result);
        model.addAttribute("waiting", "PENDING".equals(result.status()) || "PROCESSING".equals(result.status()));
        model.addAttribute("validUntil", result.voucher() == null
                ? null
                : formats.date(java.time.LocalDate.parse(result.voucher().validUntil())));
        return "hosted/result";
    }

    @GetMapping("/v/{slug}/podminky")
    public String terms(@PathVariable("slug") String slug, Model model) {
        TenantSettings tenant = tenant(slug);
        fill(model, tenant);
        return "hosted/terms";
    }

    /** Barva podniku jako CSS proměnná (CSP nedovoluje inline styly). */
    @GetMapping(value = "/v/{slug}/brand.css", produces = "text/css")
    public ResponseEntity<String> brand(@PathVariable("slug") String slug) {
        TenantSettings tenant = tenant(slug);
        String accent = tenant.brandColor() != null ? tenant.brandColor() : "#b1442f";
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/css"))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                .body(":root { --accent: " + accent + "; }\n");
    }

    private void fill(Model model, TenantSettings tenant) {
        model.addAttribute("tenant", tenant);
        model.addAttribute("testMode", !props.stripe().expectedLivemode());
    }

    private TenantSettings tenant(String slug) {
        Optional<Tenant> tenant = tenants.bySlug(slug);
        if (tenant.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return tenants.settings(tenant.get());
    }

    private static String firstOffer(TenantSettings tenant) {
        return tenant.offers().isEmpty() ? null : tenant.offers().get(0).id();
    }
}
