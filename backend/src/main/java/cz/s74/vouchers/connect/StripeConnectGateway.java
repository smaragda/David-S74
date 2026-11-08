package cz.s74.vouchers.connect;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.v2.core.Account;
import com.stripe.model.v2.core.AccountLink;
import com.stripe.net.RequestOptions;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.AccountLinkCreateParams;
import com.stripe.param.v2.core.AccountRetrieveParams;
import cz.s74.vouchers.stripe.PaymentProviderException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stripe Connect přes Accounts v2 ({@code /v2/core/accounts}), ne zastaralé typy standard/express/custom.
 * Nastavení odpovídá „SaaS platformě s platbami“: podnik má plný Dashboard, je prodávajícím (merchant of record),
 * poplatky Stripe platí podnik a záporné zůstatky nese Stripe (ne platforma).
 */
@Component
public class StripeConnectGateway implements ConnectGateway {

    private final StripeClient client;

    public StripeConnectGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public String createAccount(NewAccount a) {
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("connect-account-" + a.tenantId())
                .build();
        try {
            return client.v2().core().accounts().create(accountParams(a), options).getId();
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe account creation failed: " + e.getCode(), e);
        }
    }

    static AccountCreateParams accountParams(NewAccount a) {
        AccountCreateParams.Builder builder = AccountCreateParams.builder()
                .setDisplayName(a.displayName())
                .setDashboard(AccountCreateParams.Dashboard.FULL)
                .setIdentity(AccountCreateParams.Identity.builder()
                        .setCountry(a.country().toLowerCase(Locale.ROOT))
                        .build())
                .setDefaults(AccountCreateParams.Defaults.builder()
                        .setCurrency(a.currency().toLowerCase(Locale.ROOT))
                        .addLocale(AccountCreateParams.Defaults.Locale.CS_CZ)
                        .setResponsibilities(AccountCreateParams.Defaults.Responsibilities.builder()
                                .setFeesCollector(AccountCreateParams.Defaults.Responsibilities.FeesCollector.STRIPE)
                                .setLossesCollector(AccountCreateParams.Defaults.Responsibilities.LossesCollector.STRIPE)
                                .build())
                        .build())
                .setConfiguration(AccountCreateParams.Configuration.builder()
                        .setMerchant(AccountCreateParams.Configuration.Merchant.builder()
                                .setCapabilities(AccountCreateParams.Configuration.Merchant.Capabilities.builder()
                                        .setCardPayments(AccountCreateParams.Configuration.Merchant.Capabilities
                                                .CardPayments.builder()
                                                .setRequested(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addInclude(AccountCreateParams.Include.CONFIGURATION__MERCHANT)
                .addInclude(AccountCreateParams.Include.REQUIREMENTS)
                .putMetadata("tenant_id", a.tenantId().toString());
        if (a.contactEmail() != null && !a.contactEmail().isBlank()) {
            builder.setContactEmail(a.contactEmail());
        }
        return builder.build();
    }

    @Override
    public String createOnboardingLink(String accountId, String refreshUrl, String returnUrl) {
        try {
            AccountLink link = client.v2().core().accountLinks().create(linkParams(accountId, refreshUrl, returnUrl));
            return link.getUrl();
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe account link creation failed: " + e.getCode(), e);
        }
    }

    static AccountLinkCreateParams linkParams(String accountId, String refreshUrl, String returnUrl) {
        return AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setUseCase(AccountLinkCreateParams.UseCase.builder()
                        .setType(AccountLinkCreateParams.UseCase.Type.ACCOUNT_ONBOARDING)
                        .setAccountOnboarding(AccountLinkCreateParams.UseCase.AccountOnboarding.builder()
                                // SDK 34 nemá pro "configurations" typovaný setter – posílá se jako extra parametr.
                                .putExtraParam("configurations", List.of("merchant"))
                                .setRefreshUrl(refreshUrl)
                                .setReturnUrl(returnUrl)
                                .build())
                        .build())
                .build();
    }

    @Override
    public AccountStatus retrieveStatus(String accountId) {
        try {
            Account account = client.v2().core().accounts().retrieve(
                    accountId,
                    AccountRetrieveParams.builder()
                            .addInclude(AccountRetrieveParams.Include.CONFIGURATION__MERCHANT)
                            .addInclude(AccountRetrieveParams.Include.REQUIREMENTS)
                            .build());
            return toStatus(account);
        } catch (StripeException e) {
            throw new PaymentProviderException("Stripe account retrieval failed: " + e.getCode(), e);
        }
    }

    static AccountStatus toStatus(Account account) {
        String card = Optional.ofNullable(account.getConfiguration())
                .map(Account.Configuration::getMerchant)
                .map(Account.Configuration.Merchant::getCapabilities)
                .map(Account.Configuration.Merchant.Capabilities::getCardPayments)
                .map(Account.Configuration.Merchant.Capabilities.CardPayments::getStatus)
                .orElse(null);
        int due = account.getRequirements() == null || account.getRequirements().getEntries() == null
                ? 0
                : (int) account.getRequirements().getEntries().stream()
                        .filter(e -> e.getAwaitingActionFrom() == null || "user".equals(e.getAwaitingActionFrom()))
                        .count();
        return new AccountStatus(card, due, Boolean.TRUE.equals(account.getClosed()));
    }
}
