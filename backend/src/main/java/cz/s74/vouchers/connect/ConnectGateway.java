package cz.s74.vouchers.connect;

import java.util.UUID;

/** Hranice vůči Stripe Connect (Accounts v2) – zakládání účtů podniků a hostovaný onboarding. V testech atrapa. */
public interface ConnectGateway {

    /** Založí připojený účet podniku (prodávající = podnik, poplatky a ztráty nese Stripe). Vrací {@code acct_…}. */
    String createAccount(NewAccount account);

    /** Jednorázový odkaz na hostovaný onboarding Stripe (platí krátce, při expiraci se vytvoří nový). */
    String createOnboardingLink(String accountId, String refreshUrl, String returnUrl);

    /** Aktuální stav účtu přímo ze Stripe. */
    AccountStatus retrieveStatus(String accountId);

    record NewAccount(UUID tenantId, String displayName, String contactEmail, String country, String currency) {}

    /**
     * @param cardPayments stav capability {@code card_payments}: active | pending | restricted | unsupported
     *     (null = zatím nevyžádáno)
     * @param requirementsDue počet požadavků, které musí podnik doplnit
     * @param closed účet byl uzavřen
     */
    record AccountStatus(String cardPayments, int requirementsDue, boolean closed) {
        public boolean canAcceptPayments() {
            return !closed && "active".equals(cardPayments);
        }
    }
}
