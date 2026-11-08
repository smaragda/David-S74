package cz.s74.vouchers.staff;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.Formats;
import cz.s74.vouchers.config.RateLimits;
import cz.s74.vouchers.voucher.RedemptionService;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodes;
import cz.s74.vouchers.voucher.VoucherRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Rozhraní obsluhy: přihlášení, zadání kódu, detail voucheru a uplatnění (vzor PRG, vše s CSRF). */
@Controller
public class StaffController {

    private static final String MSG_BAD_FORMAT = "Kód má 12 znaků, zkontrolujte jej.";
    private static final String MSG_NOT_FOUND = "Kód nenalezen. Zkontrolujte překlep a zkuste znovu.";
    private static final String MSG_BLOCKED =
            "Příliš mnoho neúspěšných pokusů. Zkuste to za 15 minut nebo se obraťte na vedoucího.";

    private final VoucherRepository vouchers;
    private final StaffUserRepository users;
    private final RedemptionService redemption;
    private final AuditService audit;
    private final RateLimits limits;
    private final Formats formats;
    private final Clock clock;

    public StaffController(
            VoucherRepository vouchers,
            StaffUserRepository users,
            RedemptionService redemption,
            AuditService audit,
            RateLimits limits,
            Formats formats,
            Clock clock) {
        this.vouchers = vouchers;
        this.users = users;
        this.redemption = redemption;
        this.audit = audit;
        this.limits = limits;
        this.formats = formats;
        this.clock = clock;
    }

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()))
                    ? "redirect:/platform"
                    : "redirect:/staff";
        }
        return "login";
    }

    @GetMapping("/staff")
    public String index() {
        return "staff/index";
    }

    @PostMapping("/staff/lookup")
    public String lookup(
            @RequestParam("code") String input,
            @AuthenticationPrincipal StaffPrincipal staff,
            HttpServletRequest request,
            RedirectAttributes redirect) {
        Optional<String> code = VoucherCodes.normalize(input);
        if (code.isEmpty()) {
            redirect.addFlashAttribute("error", MSG_BAD_FORMAT); // chyba tvaru se do limitu nepočítá
            return "redirect:/staff";
        }
        Optional<Voucher> voucher = find(code.get(), staff, request);
        if (voucher.isEmpty()) {
            redirect.addFlashAttribute("error", isBlocked(staff, request) ? MSG_BLOCKED : MSG_NOT_FOUND);
            return "redirect:/staff";
        }
        return "redirect:/staff/v/" + code.get();
    }

    @GetMapping("/staff/v/{code}")
    public String detail(
            @PathVariable("code") String input,
            @AuthenticationPrincipal StaffPrincipal staff,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirect) {
        Optional<String> code = VoucherCodes.normalize(input);
        if (code.isEmpty()) {
            redirect.addFlashAttribute("error", MSG_BAD_FORMAT);
            return "redirect:/staff";
        }
        Optional<Voucher> voucher = find(code.get(), staff, request);
        if (voucher.isEmpty()) {
            redirect.addFlashAttribute("error", isBlocked(staff, request) ? MSG_BLOCKED : MSG_NOT_FOUND);
            return "redirect:/staff";
        }
        Voucher v = voucher.get();
        audit.staff(
                staff.getId(), "VOUCHER_VIEWED", v.getId(), v.getOrderId(), request.getRemoteAddr(),
                Map.of("code", cz.s74.vouchers.common.Masking.code(v.getCode())));
        model.addAttribute("v", view(v, staff));
        return "staff/voucher";
    }

    @PostMapping("/staff/v/{code}/redeem")
    public String redeem(
            @PathVariable("code") String input,
            @RequestParam(value = "acknowledged", required = false) String acknowledged,
            @RequestParam(value = "note", required = false) String note,
            @AuthenticationPrincipal StaffPrincipal staff,
            HttpServletRequest request,
            RedirectAttributes redirect) {
        Optional<String> code = VoucherCodes.normalize(input);
        Optional<Voucher> voucher = code.flatMap(c -> vouchers.findByTenantIdAndCode(staff.getTenantId(), c));
        if (voucher.isEmpty()) {
            redirect.addFlashAttribute("error", MSG_NOT_FOUND);
            return "redirect:/staff";
        }
        String target = "redirect:/staff/v/" + code.get();
        if (!"true".equals(acknowledged)) {
            redirect.addFlashAttribute("error", "Před uplatněním potvrďte, že jste zákazníka upozornili na propadnutí rozdílu.");
            return target;
        }
        RedemptionService.Result result = redemption.redeem(voucher.get().getId(), staff.getId(), note, request.getRemoteAddr());
        if (result == RedemptionService.Result.REJECTED) {
            redirect.addFlashAttribute("error", "Voucher se nepodařilo uplatnit – už není platný. Stav je uveden níže.");
        }
        return target;
    }

    private Optional<Voucher> find(String code, StaffPrincipal staff, HttpServletRequest request) {
        if (isBlocked(staff, request)) {
            audit.staff(staff.getId(), "RATE_LIMITED", null, null, request.getRemoteAddr(), Map.of("scope", "lookup"));
            return Optional.empty();
        }
        // Jen vouchery vlastního podniku; kód jiného podniku se chová jako neexistující (a počítá se do limitu).
        Optional<Voucher> voucher = vouchers.findByTenantIdAndCode(staff.getTenantId(), code);
        if (voucher.isEmpty()) {
            limits.lookupMissesPerAccount.hit(staff.getUsername());
            limits.lookupMissesPerIp.hit(request.getRemoteAddr());
            audit.staff(staff.getId(), "LOOKUP_NOT_FOUND", null, null, request.getRemoteAddr(), Map.of());
        }
        return voucher;
    }

    private boolean isBlocked(StaffPrincipal staff, HttpServletRequest request) {
        return limits.lookupMissesPerAccount.isBlocked(staff.getUsername())
                || limits.lookupMissesPerIp.isBlocked(request.getRemoteAddr());
    }

    private VoucherView view(Voucher v, StaffPrincipal staff) {
        String redeemedByName = v.getRedeemedBy() == null
                ? null
                : users.findById(v.getRedeemedBy()).map(StaffUser::getDisplayName).orElse("neznámý účet");
        return VoucherView.of(v, LocalDate.now(clock), Instant.now(clock), staff.getId(), redeemedByName, formats);
    }
}
