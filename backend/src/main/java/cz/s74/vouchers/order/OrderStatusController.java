package cz.s74.vouchers.order;

import cz.s74.vouchers.tenant.PublicTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stav objednávky pro návratovou stránku webu podniku. */
@RestController
@RequestMapping("/api/public")
public class OrderStatusController {

    private final OrderStatusService status;
    private final PublicTenantResolver tenants;

    public OrderStatusController(OrderStatusService status, PublicTenantResolver tenants) {
        this.status = status;
        this.tenants = tenants;
    }

    @GetMapping("/orders/status")
    public OrderStatusService.StatusResponse status(
            @RequestParam("session_id") String sessionId, HttpServletRequest http) {
        return status.status(tenants.defaultTenant(), sessionId, http.getRemoteAddr());
    }

    @GetMapping("/t/{slug}/orders/status")
    public OrderStatusService.StatusResponse status(
            @PathVariable("slug") String slug, @RequestParam("session_id") String sessionId, HttpServletRequest http) {
        return status.status(tenants.bySlug(slug), sessionId, http.getRemoteAddr());
    }
}
