package cz.s74.vouchers.tenant;

import cz.s74.vouchers.config.S74Properties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Vyhledání podniků a sestavení jejich výsledného nastavení. */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final TenantOfferRepository offers;
    private final S74Properties props;

    public TenantService(TenantRepository tenants, TenantOfferRepository offers, S74Properties props) {
        this.tenants = tenants;
        this.offers = offers;
        this.props = props;
    }

    public Tenant require(UUID id) {
        return tenants.findById(id).orElseThrow(() -> new IllegalStateException("Tenant " + id + " not found"));
    }

    public Optional<Tenant> bySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return tenants.findBySlug(slug.trim().toLowerCase());
    }

    public Optional<Tenant> byStripeAccount(String accountId) {
        return accountId == null ? Optional.empty() : tenants.findByStripeAccountId(accountId);
    }

    public TenantSettings defaultSettings() {
        return settings(require(Tenants.DEFAULT_ID));
    }

    public TenantSettings settings(UUID tenantId) {
        return settings(require(tenantId));
    }

    /**
     * Prázdné hodnoty výchozího tenanta (S74) se doplní z konfigurace {@code s74.*} – dosavadní nasazení se tak
     * nemění. Ostatní podniky mají vlastní hodnoty; co nevyplní, dostane výchozí hodnotu platformy (platnost,
     * verze podmínek) nebo hostovanou stránku (web, podmínky).
     */
    public TenantSettings settings(Tenant t) {
        String publicBase = props.publicBaseUrlNoSlash();
        String hostedUrl = publicBase + "/v/" + t.getSlug();
        boolean legacy = t.isDefault();

        String frontend = blankToNull(t.getFrontendBaseUrl());
        if (frontend == null && legacy) {
            frontend = props.frontendBaseUrlNoSlash();
        }
        frontend = frontend == null ? null : stripSlash(frontend);

        String termsUrl = blankToNull(t.getTermsUrl());
        if (termsUrl == null) {
            termsUrl = legacy ? frontend + props.voucher().termsPath() : hostedUrl + "/podminky";
        }

        LinkedHashSet<String> origins = new LinkedHashSet<>(parseOrigins(t.getCorsAllowedOrigins()));
        if (legacy) {
            origins.addAll(props.corsAllowedOrigins());
        }

        String replyTo = legacy
                ? firstNonBlank(t.getReplyTo(), props.mail().replyTo())
                : firstNonBlank(t.getReplyTo(), t.getContactEmail());

        List<TenantSettings.Offer> list = new ArrayList<>(
                offers.findByTenantIdAndActiveTrueOrderBySortOrderAscAmountMinorAsc(t.getId()).stream()
                        .map(o -> new TenantSettings.Offer(o.getOfferCode(), o.getAmountMinor()))
                        .toList());
        if (list.isEmpty() && legacy) {
            props.voucher().offers().forEach(o -> list.add(new TenantSettings.Offer(o.id(), o.amountMinor())));
        }

        return new TenantSettings(
                t.getId(),
                t.getSlug(),
                t.getDisplayName(),
                t.getLegalName(),
                t.getCompanyId(),
                t.getAddress(),
                replyTo,
                frontend,
                hostedUrl,
                termsUrl,
                t.getTermsVersion() != null ? t.getTermsVersion() : props.voucher().termsVersion(),
                t.getValidityMonths() != null ? t.getValidityMonths() : props.voucher().validityMonths(),
                t.getShowOnReturnMinutes() != null ? t.getShowOnReturnMinutes() : props.voucher().showOnReturnMinutes(),
                t.getBrandColor(),
                List.copyOf(list),
                List.copyOf(origins),
                t.getPaymentMode(),
                t.getPaymentMode() == Tenant.PaymentMode.CONNECT ? t.getStripeAccountId() : null,
                t.salesEnabled() && !list.isEmpty(),
                t.getApplicationFeeBps(),
                t.getApplicationFeeFixedMinor());
    }

    public static List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(TenantService::stripSlash)
                .toList();
    }

    static String stripSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
