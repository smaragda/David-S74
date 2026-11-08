package cz.s74.vouchers.platform;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.common.EmailAddresses;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantOffer;
import cz.s74.vouchers.tenant.TenantOfferRepository;
import cz.s74.vouchers.tenant.TenantRepository;
import cz.s74.vouchers.tenant.TenantService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Správa podniků správcem platformy: založení, profil, prodej, ceník, provize, pozastavení. Vše auditované. */
@Service
public class TenantAdminService {

    static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern COMPANY_ID = Pattern.compile("^[0-9]{6,10}$");
    /** Slugy, které by kolidovaly s adresami aplikace nebo mátly zákazníky. */
    private static final Set<String> RESERVED = Set.of(
            "admin", "api", "platform", "staff", "login", "logout", "static", "assets", "www", "app", "stripe", "v");
    static final long MIN_OFFER_CZK = 50;
    static final long MAX_OFFER_CZK = 100_000;
    static final int MAX_OFFERS = 8;

    private final TenantRepository tenants;
    private final TenantOfferRepository offers;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TenantAdminService(
            TenantRepository tenants,
            TenantOfferRepository offers,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock) {
        this.tenants = tenants;
        this.offers = offers;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public record NewTenant(
            String slug,
            String displayName,
            String legalName,
            String companyId,
            String address,
            String contactEmail,
            String offersCzk) {}

    public record Profile(
            String displayName,
            String legalName,
            String companyId,
            String address,
            String contactEmail,
            String replyTo) {}

    public record Sales(
            String frontendBaseUrl,
            String corsAllowedOrigins,
            String termsUrl,
            String termsVersion,
            Integer validityMonths,
            Integer showOnReturnMinutes,
            String brandColor) {}

    /** Nový podnik: vždy přímé platby na vlastní připojený účet (CONNECT); prodávat začne po dokončení onboardingu. */
    public Tenant create(UUID actorId, NewTenant cmd, String ip) {
        String slug = cmd.slug() == null ? "" : cmd.slug().trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches() || RESERVED.contains(slug)) {
            throw new BusinessRuleException(
                    "Adresa (slug) musí mít 1–40 znaků: malá písmena bez diakritiky, číslice a pomlčky (ne na okraji).");
        }
        String name = required(cmd.displayName(), "Vyplňte název podniku.", 120);
        String email = email(cmd.contactEmail(), true);
        List<Long> amounts = parseOffers(cmd.offersCzk());
        Instant now = Instant.now(clock);
        return tx.execute(s -> {
            if (tenants.existsBySlug(slug)) {
                throw new BusinessRuleException("Podnik s touto adresou už existuje.");
            }
            Tenant t = new Tenant(UUID.randomUUID(), slug, name, Tenant.PaymentMode.CONNECT, now);
            t.updateProfile(name, optional(cmd.legalName(), 200), companyId(cmd.companyId()),
                    optional(cmd.address(), 255), email, null, now);
            tenants.saveAndFlush(t);
            storeOffers(t.getId(), amounts);
            audit.tenant(t.getId(), AuditService.Actor.STAFF, actorId, "TENANT_CREATED", ip,
                    Map.of("slug", slug, "offers", amounts.toString()));
            return t;
        });
    }

    public void updateProfile(UUID actorId, UUID tenantId, Profile p, String ip) {
        String name = required(p.displayName(), "Vyplňte název podniku.", 120);
        String contact = email(p.contactEmail(), !tenantId.equals(cz.s74.vouchers.tenant.Tenants.DEFAULT_ID));
        String replyTo = email(p.replyTo(), false);
        tx.executeWithoutResult(s -> {
            Tenant t = load(tenantId);
            t.updateProfile(name, optional(p.legalName(), 200), companyId(p.companyId()), optional(p.address(), 255),
                    contact, replyTo, Instant.now(clock));
            tenants.save(t);
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "TENANT_PROFILE_UPDATED", ip, Map.of());
        });
    }

    public void updateSales(UUID actorId, UUID tenantId, Sales p, String ip) {
        String frontend = url(p.frontendBaseUrl(), "Adresa webu podniku");
        String termsUrl = url(p.termsUrl(), "Adresa obchodních podmínek");
        String origins = origins(p.corsAllowedOrigins());
        String version = optional(p.termsVersion(), 32);
        if (p.validityMonths() != null && (p.validityMonths() < 1 || p.validityMonths() > 120)) {
            throw new BusinessRuleException("Platnost voucheru musí být 1–120 měsíců.");
        }
        if (p.showOnReturnMinutes() != null && (p.showOnReturnMinutes() < 0 || p.showOnReturnMinutes() > 1440)) {
            throw new BusinessRuleException("Zobrazení voucheru po platbě: 0–1440 minut.");
        }
        String color = optional(p.brandColor(), 7);
        if (color != null && !COLOR.matcher(color).matches()) {
            throw new BusinessRuleException("Barva musí být ve tvaru #RRGGBB.");
        }
        tx.executeWithoutResult(s -> {
            Tenant t = load(tenantId);
            t.updateSales(frontend, origins, termsUrl, version, p.validityMonths(), p.showOnReturnMinutes(), color,
                    Instant.now(clock));
            tenants.save(t);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("frontend", frontend == null ? "" : frontend);
            details.put("termsVersion", version == null ? "" : version);
            details.put("validityMonths", p.validityMonths() == null ? "" : p.validityMonths());
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "TENANT_SALES_UPDATED", ip, details);
        });
    }

    /** Provize platformy z každé platby (jen u přímých plateb na připojený účet). */
    public void updateFees(UUID actorId, UUID tenantId, int bps, long fixedCzk, String ip) {
        if (bps < 0 || bps > 2000) {
            throw new BusinessRuleException("Procentní provize musí být 0–20 % (0–2000 bazických bodů).");
        }
        if (fixedCzk < 0 || fixedCzk > 1000) {
            throw new BusinessRuleException("Pevná provize musí být 0–1000 Kč.");
        }
        tx.executeWithoutResult(s -> {
            Tenant t = load(tenantId);
            t.updateFees(bps, fixedCzk * 100, Instant.now(clock));
            tenants.save(t);
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "TENANT_FEES_UPDATED", ip,
                    Map.of("bps", bps, "fixedCzk", fixedCzk));
        });
    }

    public void updateOffers(UUID actorId, UUID tenantId, String offersCzk, String ip) {
        List<Long> amounts = parseOffers(offersCzk);
        tx.executeWithoutResult(s -> {
            load(tenantId);
            storeOffers(tenantId, amounts);
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "TENANT_OFFERS_UPDATED", ip,
                    Map.of("offers", amounts.toString()));
        });
    }

    public void changeStatus(UUID actorId, UUID tenantId, Tenant.Status status, String reason, String ip) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3) {
            throw new BusinessRuleException("Uveďte důvod (alespoň 3 znaky).");
        }
        tx.executeWithoutResult(s -> {
            Tenant t = load(tenantId);
            t.changeStatus(status, Instant.now(clock));
            tenants.save(t);
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId,
                    status == Tenant.Status.SUSPENDED ? "TENANT_SUSPENDED" : "TENANT_ACTIVATED", ip,
                    Map.of("reason", why.length() > 255 ? why.substring(0, 255) : why));
        });
    }

    /** Ceník: vypnout hodnoty, které v seznamu nejsou, zapnout/založit uvedené v daném pořadí. Staré objednávky se nemění. */
    private void storeOffers(UUID tenantId, List<Long> amountsCzk) {
        Map<String, TenantOffer> existing = new LinkedHashMap<>();
        offers.findByTenantId(tenantId).forEach(o -> existing.put(o.getOfferCode(), o));
        Set<String> wanted = new LinkedHashSet<>();
        int order = 0;
        for (long czk : amountsCzk) {
            String code = "CZK_" + czk;
            wanted.add(code);
            TenantOffer o = existing.get(code);
            if (o == null) {
                offers.save(new TenantOffer(UUID.randomUUID(), tenantId, code, czk * 100, order));
            } else {
                o.activate(order);
                offers.save(o);
            }
            order++;
        }
        existing.values().stream().filter(o -> !wanted.contains(o.getOfferCode())).forEach(o -> {
            o.deactivate();
            offers.save(o);
        });
    }

    static List<Long> parseOffers(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleException("Zadejte alespoň jednu hodnotu voucheru (např. 500, 1000, 2000).");
        }
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            long czk;
            try {
                czk = Long.parseLong(part.replace(" ", "").replace("Kč", ""));
            } catch (NumberFormatException e) {
                throw new BusinessRuleException("Hodnota „" + part + "“ není celé číslo v Kč.");
            }
            if (czk < MIN_OFFER_CZK || czk > MAX_OFFER_CZK) {
                throw new BusinessRuleException(
                        "Hodnota voucheru musí být " + MIN_OFFER_CZK + "–" + MAX_OFFER_CZK + " Kč.");
            }
            values.add(czk);
        }
        if (values.isEmpty() || values.size() > MAX_OFFERS) {
            throw new BusinessRuleException("Zadejte 1–" + MAX_OFFERS + " hodnot voucheru.");
        }
        return new ArrayList<>(values);
    }

    private Tenant load(UUID id) {
        return tenants.findById(id).orElseThrow(() -> new BusinessRuleException("Podnik neexistuje."));
    }

    private static String required(String value, String message, int max) {
        String v = optional(value, max);
        if (v == null) {
            throw new BusinessRuleException(message);
        }
        return v;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new BusinessRuleException("Text je příliš dlouhý (max " + max + " znaků).");
        }
        return v;
    }

    private static String companyId(String raw) {
        String v = optional(raw, 20);
        if (v != null && !COMPANY_ID.matcher(v.replace(" ", "")).matches()) {
            throw new BusinessRuleException("IČO musí obsahovat 6–10 číslic.");
        }
        return v == null ? null : v.replace(" ", "");
    }

    private static String email(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new BusinessRuleException("Vyplňte kontaktní e-mail podniku.");
            }
            return null;
        }
        return EmailAddresses.normalize(raw).orElseThrow(() -> new BusinessRuleException("Neplatná e-mailová adresa."));
    }

    /** https:// adresa (http jen pro localhost / 127.0.0.1 při vývoji), bez lomítka na konci. */
    static String url(String raw, String label) {
        String v = optional(raw, 255);
        if (v == null) {
            return null;
        }
        try {
            URI uri = URI.create(v);
            String host = uri.getHost();
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
            if (host == null || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && local))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(label + " musí být https:// adresa bez parametrů.");
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** Originy pro CORS: schéma + host (+ port), bez cesty. */
    static String origins(String raw) {
        List<String> list = TenantService.parseOrigins(raw);
        if (list.isEmpty()) {
            return null;
        }
        for (String origin : list) {
            String u = url(origin, "Origin " + origin);
            URI uri = URI.create(u);
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new BusinessRuleException("Origin " + origin + " nesmí obsahovat cestu (jen https://domena.cz).");
            }
        }
        String joined = String.join(",", list);
        if (joined.length() > 1000) {
            throw new BusinessRuleException("Příliš mnoho originů.");
        }
        return joined;
    }
}
