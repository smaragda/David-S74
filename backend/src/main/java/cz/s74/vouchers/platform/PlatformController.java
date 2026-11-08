package cz.s74.vouchers.platform;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.connect.ConnectService;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.OrderStatus;
import cz.s74.vouchers.staff.StaffAccountService;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUserRepository;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantOfferRepository;
import cz.s74.vouchers.tenant.TenantRepository;
import cz.s74.vouchers.tenant.TenantService;
import cz.s74.vouchers.tenant.TenantSettings;
import cz.s74.vouchers.voucher.VoucherRepository;
import cz.s74.vouchers.voucher.VoucherStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Rozhraní správce platformy (role PLATFORM_ADMIN). Zakládá podniky, jejich první vedoucí a připojení ke Stripe.
 * Zákaznická data (e-maily, kódy) záměrně nevidí – jen souhrnná čísla.
 */
@Controller
public class PlatformController {

    private final TenantRepository tenants;
    private final TenantService tenantService;
    private final TenantOfferRepository offers;
    private final TenantAdminService admin;
    private final ConnectService connect;
    private final StaffAccountService accounts;
    private final StaffUserRepository users;
    private final OrderRepository orders;
    private final VoucherRepository vouchers;
    private final AuditService audit;
    private final S74Properties props;

    public PlatformController(
            TenantRepository tenants,
            TenantService tenantService,
            TenantOfferRepository offers,
            TenantAdminService admin,
            ConnectService connect,
            StaffAccountService accounts,
            StaffUserRepository users,
            OrderRepository orders,
            VoucherRepository vouchers,
            AuditService audit,
            S74Properties props) {
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.offers = offers;
        this.admin = admin;
        this.connect = connect;
        this.accounts = accounts;
        this.users = users;
        this.orders = orders;
        this.vouchers = vouchers;
        this.audit = audit;
        this.props = props;
    }

    public record Row(Tenant tenant, TenantSettings settings, long paidOrders, long activeVouchers) {}

    @GetMapping("/platform")
    public String index(Model model) {
        List<Row> rows = tenants.findAllByOrderByDisplayNameAsc().stream()
                .map(t -> new Row(
                        t,
                        tenantService.settings(t),
                        orders.countByTenantIdAndStatus(t.getId(), OrderStatus.PAID),
                        vouchers.countByTenantIdAndStatus(t.getId(), VoucherStatus.ACTIVE)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("connectEnabled", props.connect().enabled());
        return "platform/index";
    }

    @PostMapping("/platform/tenants")
    public String create(
            @RequestParam("slug") String slug,
            @RequestParam("displayName") String displayName,
            @RequestParam(value = "legalName", required = false) String legalName,
            @RequestParam(value = "companyId", required = false) String companyId,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam("contactEmail") String contactEmail,
            @RequestParam("offers") String offersCzk,
            @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request,
            RedirectAttributes redirect) {
        try {
            Tenant t = admin.create(me.getId(), new TenantAdminService.NewTenant(
                    slug, displayName, legalName, companyId, address, contactEmail, offersCzk), request.getRemoteAddr());
            redirect.addFlashAttribute("info", "Podnik byl založen. Založte mu vedoucího a připojte Stripe účet.");
            return "redirect:/platform/tenants/" + t.getId();
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/platform";
        }
    }

    @GetMapping("/platform/tenants/{id}")
    public String detail(@PathVariable("id") UUID id, Model model, RedirectAttributes redirect) {
        Tenant t = tenants.findById(id).orElse(null);
        if (t == null) {
            redirect.addFlashAttribute("error", "Podnik neexistuje.");
            return "redirect:/platform";
        }
        TenantSettings settings = tenantService.settings(t);
        model.addAttribute("t", t);
        model.addAttribute("s", settings);
        model.addAttribute("offersText", offers.findByTenantIdAndActiveTrueOrderBySortOrderAscAmountMinorAsc(id).stream()
                .map(o -> Long.toString(o.getAmountMinor() / 100))
                .collect(Collectors.joining(", ")));
        model.addAttribute("managers", users.findAllByTenantIdOrderByUsernameAsc(id).stream()
                .filter(u -> u.getRole() == StaffRole.MANAGER)
                .toList());
        model.addAttribute("history", audit.forTenantAdministration(id, 30));
        model.addAttribute("connectEnabled", props.connect().enabled());
        model.addAttribute("minPassword", StaffAccountService.MIN_PASSWORD_LENGTH);
        model.addAttribute("stripeDashboardUrl", t.getStripeAccountId() == null
                ? null
                : "https://dashboard.stripe.com/" + (props.stripe().expectedLivemode() ? "" : "test/")
                        + "connect/accounts/" + t.getStripeAccountId());
        return "platform/tenant";
    }

    @PostMapping("/platform/tenants/{id}/profile")
    public String profile(@PathVariable("id") UUID id,
            @RequestParam("displayName") String displayName,
            @RequestParam(value = "legalName", required = false) String legalName,
            @RequestParam(value = "companyId", required = false) String companyId,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "contactEmail", required = false) String contactEmail,
            @RequestParam(value = "replyTo", required = false) String replyTo,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Profil podniku uložen.", () -> admin.updateProfile(me.getId(), id,
                new TenantAdminService.Profile(displayName, legalName, companyId, address, contactEmail, replyTo),
                request.getRemoteAddr()));
    }

    @PostMapping("/platform/tenants/{id}/sales")
    public String sales(@PathVariable("id") UUID id,
            @RequestParam(value = "frontendBaseUrl", required = false) String frontendBaseUrl,
            @RequestParam(value = "corsAllowedOrigins", required = false) String corsAllowedOrigins,
            @RequestParam(value = "termsUrl", required = false) String termsUrl,
            @RequestParam(value = "termsVersion", required = false) String termsVersion,
            @RequestParam(value = "validityMonths", required = false) Integer validityMonths,
            @RequestParam(value = "showOnReturnMinutes", required = false) Integer showOnReturnMinutes,
            @RequestParam(value = "brandColor", required = false) String brandColor,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Nastavení prodeje uloženo.", () -> admin.updateSales(me.getId(), id,
                new TenantAdminService.Sales(frontendBaseUrl, corsAllowedOrigins, termsUrl, termsVersion, validityMonths,
                        showOnReturnMinutes, brandColor),
                request.getRemoteAddr()));
    }

    @PostMapping("/platform/tenants/{id}/offers")
    public String offers(@PathVariable("id") UUID id, @RequestParam("offers") String offersCzk,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Ceník uložen.",
                () -> admin.updateOffers(me.getId(), id, offersCzk, request.getRemoteAddr()));
    }

    @PostMapping("/platform/tenants/{id}/fees")
    public String fees(@PathVariable("id") UUID id,
            @RequestParam(value = "feePercent", defaultValue = "0") String feePercent,
            @RequestParam(value = "feeFixedCzk", defaultValue = "0") long feeFixedCzk,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Provize uložena.", () -> {
            int bps;
            try {
                bps = new java.math.BigDecimal(feePercent.replace(',', '.').trim())
                        .movePointRight(2)
                        .intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new BusinessRuleException("Procento zadejte jako číslo s nejvýše dvěma desetinnými místy.");
            }
            admin.updateFees(me.getId(), id, bps, feeFixedCzk, request.getRemoteAddr());
        });
    }

    @PostMapping("/platform/tenants/{id}/managers")
    public String createManager(@PathVariable("id") UUID id,
            @RequestParam("username") String username, @RequestParam("displayName") String displayName,
            @RequestParam("password") String password,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Účet vedoucího byl založen. Předejte mu přihlašovací údaje bezpečnou cestou.",
                () -> {
                    if (tenants.findById(id).isEmpty()) {
                        throw new BusinessRuleException("Podnik neexistuje.");
                    }
                    accounts.create(me.getId(), id, username, displayName, StaffRole.MANAGER, password,
                            request.getRemoteAddr());
                });
    }

    @PostMapping("/platform/tenants/{id}/suspend")
    public String suspend(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Prodej podniku byl pozastaven (už prodané vouchery lze dál uplatnit).",
                () -> admin.changeStatus(me.getId(), id, Tenant.Status.SUSPENDED, reason, request.getRemoteAddr()));
    }

    @PostMapping("/platform/tenants/{id}/activate")
    public String activate(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Podnik byl znovu aktivován.",
                () -> admin.changeStatus(me.getId(), id, Tenant.Status.ACTIVE, reason, request.getRemoteAddr()));
    }

    // --- Stripe Connect ---------------------------------------------------------------------

    @PostMapping("/platform/tenants/{id}/stripe/onboard")
    public String onboard(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request, RedirectAttributes redirect) {
        return redirectToOnboarding(id, me, request, redirect);
    }

    /** Stripe sem přesměruje, když odkaz na onboarding vypršel – vytvoří se nový. */
    @GetMapping("/platform/tenants/{id}/stripe/refresh-link")
    public String refreshLink(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request, RedirectAttributes redirect) {
        return redirectToOnboarding(id, me, request, redirect);
    }

    @GetMapping("/platform/tenants/{id}/stripe/return")
    public String stripeReturn(@PathVariable("id") UUID id, RedirectAttributes redirect) {
        return refreshStatus(id, redirect);
    }

    @PostMapping("/platform/tenants/{id}/stripe/refresh")
    public String refresh(@PathVariable("id") UUID id, RedirectAttributes redirect) {
        return refreshStatus(id, redirect);
    }

    @PostMapping("/platform/tenants/{id}/payment-mode/connect")
    public String switchToConnect(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, id, "Podnik nyní přijímá platby přímo na svůj připojený Stripe účet.",
                () -> connect.switchToConnect(id, me.getId(), request.getRemoteAddr()));
    }

    private String redirectToOnboarding(UUID id, StaffPrincipal me, HttpServletRequest request,
            RedirectAttributes redirect) {
        String base = props.publicBaseUrlNoSlash() + "/platform/tenants/" + id + "/stripe";
        try {
            return "redirect:" + connect.onboardingLink(
                    id, me.getId(), request.getRemoteAddr(), base + "/refresh-link", base + "/return");
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (PaymentProviderException e) {
            redirect.addFlashAttribute("error", "Stripe je teď nedostupný nebo odmítl požadavek: " + e.getMessage());
        }
        return "redirect:/platform/tenants/" + id;
    }

    private String refreshStatus(UUID id, RedirectAttributes redirect) {
        try {
            var status = connect.refresh(id);
            redirect.addFlashAttribute("info", status.canAcceptPayments()
                    ? "Stripe účet je aktivní – podnik může prodávat."
                    : "Stripe účet zatím nesmí přijímat platby (stav: " + status.cardPayments()
                            + ", chybějící údaje: " + status.requirementsDue() + ").");
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (PaymentProviderException e) {
            redirect.addFlashAttribute("error", "Stav ze Stripe se nepodařilo načíst: " + e.getMessage());
        }
        return "redirect:/platform/tenants/" + id;
    }

    private String act(RedirectAttributes redirect, UUID id, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("info", success);
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/platform/tenants/" + id;
    }
}
