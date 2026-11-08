package cz.s74.vouchers.admin;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.common.Formats;
import cz.s74.vouchers.email.EmailDelivery;
import cz.s74.vouchers.email.EmailDeliveryRepository;
import cz.s74.vouchers.order.OrderRepository;
import cz.s74.vouchers.order.VoucherOrder;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.staff.StaffRole;
import cz.s74.vouchers.staff.StaffUser;
import cz.s74.vouchers.staff.StaffUserRepository;
import cz.s74.vouchers.staff.StaffAccountService;
import cz.s74.vouchers.staff.VoucherView;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Rozhraní vedoucího (role MANAGER, hlídá SecurityConfig). Každý zásah vyžaduje důvod a je v auditu. */
@Controller
public class AdminController {

    private final VoucherAdminService admin;
    private final StaffAccountService accounts;
    private final StaffUserRepository users;
    private final VoucherRepository vouchers;
    private final OrderRepository orders;
    private final EmailDeliveryRepository deliveries;
    private final AuditService audit;
    private final ExportService export;
    private final Formats formats;
    private final Clock clock;

    public AdminController(
            VoucherAdminService admin,
            StaffAccountService accounts,
            StaffUserRepository users,
            VoucherRepository vouchers,
            OrderRepository orders,
            EmailDeliveryRepository deliveries,
            AuditService audit,
            ExportService export,
            Formats formats,
            Clock clock) {
        this.admin = admin;
        this.accounts = accounts;
        this.users = users;
        this.vouchers = vouchers;
        this.orders = orders;
        this.deliveries = deliveries;
        this.audit = audit;
        this.export = export;
        this.formats = formats;
        this.clock = clock;
    }

    @GetMapping("/admin")
    public String root() {
        return "redirect:/admin/vouchers";
    }

    // --- hledání a detail -------------------------------------------------------------------

    public record Row(UUID id, VoucherView view, String email, String orderRef) {}

    @GetMapping("/admin/vouchers")
    public String search(
            @RequestParam(value = "q", required = false) String q,
            @AuthenticationPrincipal StaffPrincipal me,
            Model model) {
        List<Voucher> found = admin.search(me.getTenantId(), q);
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);
        List<Row> rows = found.stream()
                .map(v -> {
                    VoucherOrder o = orders.findById(v.getOrderId()).orElseThrow();
                    return new Row(v.getId(), VoucherView.of(v, today, now, me.getId(), null, formats),
                            o.getCustomerEmail(), o.orderRef());
                })
                .toList();
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("searched", q != null && !q.isBlank());
        model.addAttribute("rows", rows);
        return "admin/vouchers";
    }

    @GetMapping("/admin/vouchers/{id}")
    public String detail(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me, Model model,
            RedirectAttributes redirect) {
        VoucherAdminService.Detail d;
        try {
            d = admin.detail(me.getTenantId(), id);
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/vouchers";
        }
        String redeemedBy = d.voucher().getRedeemedBy() == null
                ? null
                : users.findById(d.voucher().getRedeemedBy()).map(StaffUser::getDisplayName).orElse("neznámý účet");
        model.addAttribute("v", VoucherView.of(d.voucher(), LocalDate.now(clock), Instant.now(clock), me.getId(), redeemedBy, formats));
        model.addAttribute("id", id);
        model.addAttribute("order", d.order());
        model.addAttribute("orderAmount", formats.money(d.order().getAmountMinor()));
        model.addAttribute("deliveries", d.deliveries());
        model.addAttribute("history", d.history());
        model.addAttribute("voucher", d.voucher());
        model.addAttribute("stripeUrl", d.order().getStripePaymentIntentId() == null
                ? null
                : "https://dashboard.stripe.com/payments/" + d.order().getStripePaymentIntentId());
        model.addAttribute("today", LocalDate.now(clock).toString());
        return "admin/voucher";
    }

    @PostMapping("/admin/vouchers/{id}/resend")
    public String resend(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @RequestParam(value = "email", required = false) String email,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/vouchers/" + id, "E-mail s voucherem byl zařazen k odeslání.",
                () -> admin.resend(me.getId(), me.getTenantId(), id, email, reason, request.getRemoteAddr()));
    }

    @PostMapping("/admin/vouchers/{id}/cancel")
    public String cancel(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/vouchers/" + id, "Voucher byl zrušen.",
                () -> admin.cancel(me.getId(), me.getTenantId(), id, reason, request.getRemoteAddr()));
    }

    @PostMapping("/admin/vouchers/{id}/reactivate")
    public String reactivate(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/vouchers/" + id, "Voucher byl znovu aktivován.",
                () -> admin.reactivate(me.getId(), me.getTenantId(), id, reason, request.getRemoteAddr()));
    }

    @PostMapping("/admin/vouchers/{id}/revert")
    public String revert(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/vouchers/" + id, "Uplatnění bylo vráceno, voucher je opět platný.",
                () -> admin.revertRedemption(me.getId(), me.getTenantId(), id, reason, request.getRemoteAddr()));
    }

    @PostMapping("/admin/vouchers/{id}/extend")
    public String extend(@PathVariable("id") UUID id, @RequestParam("reason") String reason,
            @RequestParam("validUntil") String validUntil,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        LocalDate date;
        try {
            date = LocalDate.parse(validUntil);
        } catch (DateTimeParseException | NullPointerException e) {
            redirect.addFlashAttribute("error", "Zadejte datum platnosti ve tvaru RRRR-MM-DD.");
            return "redirect:/admin/vouchers/" + id;
        }
        return act(redirect, "/admin/vouchers/" + id, "Platnost byla upravena.",
                () -> admin.extendValidity(me.getId(), me.getTenantId(), id, date, reason, request.getRemoteAddr()));
    }

    // --- k řešení ---------------------------------------------------------------------------

    public record AttentionRow(VoucherOrder order, UUID voucherId, String amount) {}

    @GetMapping("/admin/attention")
    public String attention(@AuthenticationPrincipal StaffPrincipal me, Model model) {
        List<AttentionRow> rows = orders.findByTenantIdAndAttentionRequiredTrueOrderByUpdatedAtDesc(me.getTenantId()).stream()
                .map(o -> new AttentionRow(o, vouchers.findByOrderId(o.getId()).map(Voucher::getId).orElse(null),
                        formats.money(o.getAmountMinor())))
                .toList();
        List<EmailDelivery> failed = deliveries.findFailed(me.getTenantId());
        model.addAttribute("rows", rows);
        model.addAttribute("failed", failed);
        model.addAttribute("events", audit.recent(me.getTenantId(), List.of("RATE_LIMITED", "LOGIN_LOCKED"), 30));
        return "admin/attention";
    }

    @PostMapping("/admin/attention/{orderId}/resolve")
    public String resolve(@PathVariable("orderId") UUID orderId, @RequestParam("note") String note,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/attention", "Označeno jako vyřešené.",
                () -> admin.resolveAttention(me.getId(), me.getTenantId(), orderId, note, request.getRemoteAddr()));
    }

    // --- účty obsluhy -----------------------------------------------------------------------

    @GetMapping("/admin/staff")
    public String staff(@AuthenticationPrincipal StaffPrincipal me, Model model) {
        model.addAttribute("accounts", users.findAllByTenantIdOrderByUsernameAsc(me.getTenantId()));
        model.addAttribute("minPassword", StaffAccountService.MIN_PASSWORD_LENGTH);
        return "admin/staff";
    }

    @PostMapping("/admin/staff")
    public String createStaff(@RequestParam("username") String username, @RequestParam("displayName") String displayName,
            @RequestParam("role") String role, @RequestParam("password") String password,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        StaffRole parsed;
        try {
            parsed = StaffRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "Vyberte roli.");
            return "redirect:/admin/staff";
        }
        return act(redirect, "/admin/staff", "Účet byl založen.",
                () -> accounts.create(me.getId(), me.getTenantId(), username, displayName, parsed, password, request.getRemoteAddr()));
    }

    @PostMapping("/admin/staff/{id}/disable")
    public String disableStaff(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/staff", "Účet byl zneplatněn a jeho relace ukončeny.",
                () -> accounts.disable(me.getId(), me.getTenantId(), id, request.getRemoteAddr()));
    }

    @PostMapping("/admin/staff/{id}/enable")
    public String enableStaff(@PathVariable("id") UUID id, @AuthenticationPrincipal StaffPrincipal me,
            HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/staff", "Účet byl obnoven.",
                () -> accounts.enable(me.getId(), me.getTenantId(), id, request.getRemoteAddr()));
    }

    @PostMapping("/admin/staff/{id}/password")
    public String resetPassword(@PathVariable("id") UUID id, @RequestParam("password") String password,
            @AuthenticationPrincipal StaffPrincipal me, HttpServletRequest request, RedirectAttributes redirect) {
        return act(redirect, "/admin/staff", "Heslo bylo změněno a relace účtu ukončeny.",
                () -> accounts.resetPassword(me.getId(), me.getTenantId(), id, password, request.getRemoteAddr()));
    }

    // --- export -----------------------------------------------------------------------------

    @GetMapping("/admin/export")
    public String exportPage(@RequestParam(value = "month", required = false) String month,
            @AuthenticationPrincipal StaffPrincipal me, Model model,
            HttpServletResponse response, RedirectAttributes redirect) throws IOException {
        if (month == null || month.isBlank()) {
            model.addAttribute("defaultMonth", YearMonth.now(clock).toString());
            return "admin/export";
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("error", "Zadejte měsíc ve tvaru RRRR-MM.");
            return "redirect:/admin/export";
        }
        byte[] csv = export.csv(me.getTenantId(), ym, LocalDate.now(clock));
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("vouchery-" + ym + ".csv").build().toString());
        response.getOutputStream().write(csv);
        return null;
    }

    // ---------------------------------------------------------------------------------------

    private String act(RedirectAttributes redirect, String target, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("info", success);
        } catch (BusinessRuleException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (OptimisticLockingFailureException e) {
            redirect.addFlashAttribute("error", "Voucher se mezitím změnil (např. byl uplatněn). Zkontrolujte jeho stav a zkuste to znovu.");
        }
        return "redirect:" + target;
    }
}
