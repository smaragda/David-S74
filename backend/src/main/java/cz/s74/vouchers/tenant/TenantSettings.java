package cz.s74.vouchers.tenant;

import cz.s74.vouchers.common.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Výsledné nastavení podniku pro prodej, e-maily a stránky: hodnoty z DB doplněné výchozími hodnotami
 * (viz {@link TenantService#settings(Tenant)}). Neměnný snímek – při změně tenanta se sestaví znovu.
 */
public record TenantSettings(
        UUID id,
        String slug,
        String displayName,
        String legalName,
        String companyId,
        String address,
        String replyTo,
        /** Vlastní web podniku (bez lomítka na konci), nebo null = jen hostované stránky platformy. */
        String frontendBaseUrl,
        /** Adresa hostované prodejní stránky na doméně platformy. */
        String hostedUrl,
        String termsUrl,
        String termsVersion,
        int validityMonths,
        int showOnReturnMinutes,
        String brandColor,
        List<Offer> offers,
        List<String> corsAllowedOrigins,
        Tenant.PaymentMode paymentMode,
        /** Připojený Stripe účet, na kterém se platí (null = platformní účet). */
        String stripeAccountId,
        boolean salesEnabled,
        int applicationFeeBps,
        long applicationFeeFixedMinor) {

    public record Offer(String id, long amountMinor) {
        public long amountCzk() {
            return amountMinor / 100;
        }

        public String label() {
            return "Voucher v hodnotě " + Money.czkFromMinor(amountMinor);
        }
    }

    public Optional<Offer> offer(String offerId) {
        if (offerId == null) {
            return Optional.empty();
        }
        return offers.stream().filter(o -> o.id().equals(offerId)).findFirst();
    }

    /** Odkaz na web podniku pro e-maily (vlastní web, jinak hostovaná stránka). */
    public String siteUrl() {
        return frontendBaseUrl != null ? frontendBaseUrl : hostedUrl;
    }

    /** „S74 wine&café, s.r.o., IČO 23035111“ – patička e-mailu a podmínek. */
    public String legalLine() {
        String name = legalName != null && !legalName.isBlank() ? legalName : displayName;
        return companyId != null && !companyId.isBlank() ? name + ", IČO " + companyId : name;
    }

    /**
     * Provize platformy z platby v haléřích: pevná část + procento (v bazických bodech), zaokrouhleno dolů.
     * Jen u přímých plateb na připojený účet; nikdy ne celá částka.
     */
    public long applicationFeeFor(long amountMinor) {
        if (paymentMode != Tenant.PaymentMode.CONNECT) {
            return 0;
        }
        long fee = applicationFeeFixedMinor + amountMinor * applicationFeeBps / 10_000;
        return Math.max(0, Math.min(fee, amountMinor - 1));
    }
}
