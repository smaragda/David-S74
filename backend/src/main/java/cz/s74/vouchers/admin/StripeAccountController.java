package cz.s74.vouchers.admin;

import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.connect.ConnectService;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Vedoucí podniku: připojení vlastního Stripe účtu (hostovaný onboarding Stripe) a jeho stav.
 * Údaje o firmě a osobách sbírá Stripe, ne tato aplikace.
 */
@Controller
public class StripeAccountController {

    private final TenantService tenants;
    private final ConnectService connect;
    private final S74Properties props;

    public StripeAccountController(TenantService tenants, ConnectService connect, S74Properties props) {
        this.tenants = tenants;
        this.connect = connect;
        this.props = props;
    }

    @GetMapping("/admin/stripe")
    public String page(@AuthenticationPrincipal StaffPrincipal me, Model model) {
        Tenant t = tenants.require(me.getTenantId());
        model.addAttribute("t", t);
        model.addAttribute("s", tenants.settings(t));
        model.addAttribute("connectEnabled", props.connect().enabled());
        model.addAttribute("dashboardUrl", "https://dashboard.stripe.com/" + (props.stripe().expectedLivemode() ? "" : "test/"));
        return "admin/stripe";
    }

    @PostMapping("/admin/stripe/onboard")
    public String onboard(@AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request,
            RedirectAttributes redirect) {
        return redirectToOnboarding(me, request, redirect);
    }

    /** Stripe sem přesměruje, když odkaz vypršel nebo byl už použit – vytvoří se nový. */
    @GetMapping("/admin/stripe/refresh-link")
    public String refreshLink(@AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request,
            RedirectAttributes redirect) {
        return redirectToOnboarding(me, request, redirect);
    }

    /** Návrat z onboardingu (neznamená dokončení – stav se vždy ověří ve Stripe). */
    @GetMapping("/admin/stripe/return")
    public String stripeReturn(@AuthenticationPrincipal StaffPrincipal me, RedirectAttributes redirect) {
        try {
            var status = connect.refresh(me.getTenantId());
            redirect.addFlashAttribute("info", status.canAcceptPayments()
                    ? "Hotovo – Stripe účet je aktivní a můžete prodávat vouchery."
                    : "Stripe ještě ověřuje údaje nebo některé chybí. Stav se obnoví automaticky.");
        } catch (BusinessRuleException | PaymentProviderException e) {
            redirect.addFlashAttribute("error", "Stav účtu se nepodařilo ověřit: " + e.getMessage());
        }
        return "redirect:/admin/stripe";
    }

    private String redirectToOnboarding(StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        String base = props.publicBaseUrlNoSlash() + "/admin/stripe";
        try {
            return "redirect:" + connect.onboardingLink(
                    me.getTenantId(), me.getId(), request.getRemoteAddr(), base + "/refresh-link", base + "/return");
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (PaymentProviderException e) {
            redirect.addFlashAttribute("error", "Stripe je teď nedostupný. Zkuste to prosím za chvíli.");
        }
        return "redirect:/admin/stripe";
    }
}
