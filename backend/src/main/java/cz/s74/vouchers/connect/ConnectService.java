package cz.s74.vouchers.connect;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.common.BusinessRuleException;
import cz.s74.vouchers.config.S74Properties;
import cz.s74.vouchers.tenant.Tenant;
import cz.s74.vouchers.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Připojení podniku ke Stripe (Connect): založení účtu, hostovaný onboarding a sledování, zda účet smí
 * přijímat platby. Stav se obnovuje po návratu z onboardingu, z webhooků účtu a pravidelně pro nedokončené účty.
 */
@Service
public class ConnectService {

    private static final Logger log = LoggerFactory.getLogger(ConnectService.class);

    private final ConnectGateway gateway;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final S74Properties props;

    public ConnectService(
            ConnectGateway gateway,
            TenantRepository tenants,
            AuditService audit,
            TransactionTemplate tx,
            Clock clock,
            S74Properties props) {
        this.gateway = gateway;
        this.tenants = tenants;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    /**
     * Vrátí odkaz na hostovaný onboarding Stripe; účet podniku založí, pokud ho ještě nemá.
     * Odkaz je jednorázový – při jeho expiraci Stripe přesměruje na {@code refreshUrl}, kde se vytvoří nový.
     */
    public String onboardingLink(UUID tenantId, UUID actorId, String ip, String refreshUrl, String returnUrl) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new BusinessRuleException("Podnik neexistuje."));
        if (!props.connect().enabled()) {
            throw new BusinessRuleException("Stripe Connect není na této platformě zapnutý (S74_CONNECT_ENABLED).");
        }
        String accountId = tenant.getStripeAccountId();
        if (accountId == null) {
            accountId = gateway.createAccount(new ConnectGateway.NewAccount(
                    tenant.getId(),
                    tenant.getLegalName() != null && !tenant.getLegalName().isBlank()
                            ? tenant.getLegalName()
                            : tenant.getDisplayName(),
                    tenant.getContactEmail(),
                    props.connect().country(),
                    "CZK"));
            String created = accountId;
            tx.executeWithoutResult(s -> {
                Tenant t = tenants.findById(tenantId).orElseThrow();
                if (t.getStripeAccountId() == null) {
                    t.attachStripeAccount(created, Instant.now(clock));
                    tenants.save(t);
                    audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "STRIPE_ACCOUNT_CREATED", ip,
                            Map.of("account", created));
                }
            });
            accountId = tenants.findById(tenantId).orElseThrow().getStripeAccountId();
            log.info("Stripe account {} created for tenant {}", accountId, tenant.getSlug());
        }
        String url = gateway.createOnboardingLink(accountId, refreshUrl, returnUrl);
        audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "STRIPE_ONBOARDING_STARTED", ip, Map.of());
        return url;
    }

    /** Načte stav účtu ze Stripe a uloží ho k podniku. Vrátí aktuální stav. */
    public ConnectGateway.AccountStatus refresh(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new BusinessRuleException("Podnik neexistuje."));
        if (tenant.getStripeAccountId() == null) {
            throw new BusinessRuleException("Podnik zatím nemá připojený Stripe účet.");
        }
        ConnectGateway.AccountStatus status = gateway.retrieveStatus(tenant.getStripeAccountId());
        store(tenantId, status.closed() ? "closed" : status.cardPayments(), status.requirementsDue());
        return status;
    }

    /** Webhook účtu ({@code account.updated} nebo v2 událost účtu): obnovit stav podle ID účtu. */
    public boolean refreshByAccount(String accountId) {
        return tenants.findByStripeAccountId(accountId)
                .map(t -> {
                    refresh(t.getId());
                    return true;
                })
                .orElse(false);
    }

    /** {@code account.application.deauthorized}: podnik odpojil platformu – prodej okamžitě zastavit. */
    public boolean deauthorized(String accountId) {
        return tenants.findByStripeAccountId(accountId)
                .map(t -> {
                    store(t.getId(), "disconnected", 0);
                    log.warn("Tenant {} disconnected its Stripe account from the platform", t.getSlug());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Přepne výchozí podnik (S74) z plateb na platformní účet na přímé platby na jeho připojený účet.
     * Jen pokud připojený účet už smí přijímat karty; staré objednávky si drží svůj účet.
     */
    public void switchToConnect(UUID tenantId, UUID actorId, String ip) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenants.findById(tenantId).orElseThrow(() -> new BusinessRuleException("Podnik neexistuje."));
            if (t.getPaymentMode() == Tenant.PaymentMode.CONNECT) {
                throw new BusinessRuleException("Podnik už platí přes připojený účet.");
            }
            if (t.getStripeAccountId() == null || !"active".equals(t.getStripeCardPayments())) {
                throw new BusinessRuleException("Připojený Stripe účet ještě nesmí přijímat karty – dokončete onboarding.");
            }
            t.switchPaymentMode(Tenant.PaymentMode.CONNECT, Instant.now(clock));
            tenants.save(t);
            audit.tenant(tenantId, AuditService.Actor.STAFF, actorId, "TENANT_PAYMENT_MODE_CHANGED", ip,
                    Map.of("to", "CONNECT", "account", t.getStripeAccountId()));
        });
    }

    /** Pravidelná kontrola účtů, které ještě nejsou aktivní (záloha za webhooky). */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void refreshPending() {
        for (Tenant t : tenants.findAll()) {
            if (t.getStripeAccountId() != null
                    && !"active".equals(t.getStripeCardPayments())
                    && !"disconnected".equals(t.getStripeCardPayments())
                    && !"closed".equals(t.getStripeCardPayments())) {
                try {
                    refresh(t.getId());
                } catch (RuntimeException e) {
                    log.warn("Refreshing Stripe status of tenant {} failed: {}", t.getSlug(), e.getMessage());
                }
            }
        }
    }

    private void store(UUID tenantId, String cardPayments, int due) {
        tx.executeWithoutResult(s -> {
            Tenant t = tenants.findById(tenantId).orElseThrow();
            String before = t.getStripeCardPayments();
            t.recordStripeStatus(cardPayments, due, Instant.now(clock));
            tenants.save(t);
            if (!Objects.equals(before, cardPayments)) {
                Map<String, Object> details = new HashMap<>();
                details.put("from", before == null ? "" : before);
                details.put("to", cardPayments == null ? "" : cardPayments);
                details.put("requirementsDue", due);
                audit.tenant(tenantId, AuditService.Actor.STRIPE, null, "STRIPE_STATUS_CHANGED", null, details);
            }
        });
    }
}
