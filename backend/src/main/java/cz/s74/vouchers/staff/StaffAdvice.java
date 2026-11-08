package cz.s74.vouchers.staff;

import cz.s74.vouchers.tenant.TenantRepository;
import cz.s74.vouchers.tenant.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(annotations = Controller.class)
public class StaffAdvice {

    private final TenantRepository tenants;

    public StaffAdvice(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** Přihlášený uživatel dostupný ve všech šablonách jako {@code user}. */
    @ModelAttribute("user")
    StaffPrincipal user(@AuthenticationPrincipal Object principal) {
        return principal instanceof StaffPrincipal p ? p : null;
    }

    /** Název podniku přihlášeného uživatele pro hlavičku stránek ({@code tenantName}). */
    @ModelAttribute("tenantName")
    String tenantName(@AuthenticationPrincipal Object principal) {
        if (principal instanceof StaffPrincipal p && p.getTenantId() != null) {
            return tenants.findById(p.getTenantId()).map(Tenant::getDisplayName).orElse(null);
        }
        return null;
    }
}
