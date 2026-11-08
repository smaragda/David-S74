package cz.s74.vouchers.connect;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.stripe.PaymentProviderException;
import cz.s74.vouchers.stripe.StripeCheckoutGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Volitelný test proti skutečnému Stripe sandboxu (síť + testovací klíč). Spouští se jen ručně:
 *
 * <pre>STRIPE_SMOKE=1 mvn test -Dtest=StripeSandboxSmokeTest</pre>
 *
 * Klíč se vezme z proměnné {@code STRIPE_API_KEY}, jinak z {@code backend/.env.test} nebo {@code backend/.env}
 * (řádek {@code STRIPE_API_KEY=sk_test_…}). Ostrý klíč test odmítne. Platformní účet musí mít zapnutý Connect.
 * Vytvoří v sandboxu jeden připojený účet (dá se smazat v Dashboardu → Connect → Accounts).
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SMOKE", matches = "1")
class StripeSandboxSmokeTest {

    private static String apiKey() throws IOException {
        String key = System.getenv("STRIPE_API_KEY");
        for (String file : new String[] {".env.test", ".env"}) {
            if (key != null && !key.isBlank()) {
                break;
            }
            Path p = Path.of(file);
            if (Files.exists(p)) {
                key = Files.readAllLines(p).stream()
                        .filter(l -> l.startsWith("STRIPE_API_KEY="))
                        .map(l -> l.substring("STRIPE_API_KEY=".length()).trim())
                        .findFirst()
                        .orElse(null);
            }
        }
        assertThat(key).as("STRIPE_API_KEY (sandbox)").isNotBlank();
        assertThat(key).as("jen testovací klíč").matches("^(sk|rk)_test_.+");
        return key;
    }

    @Test
    void connectOnboardingAndCheckoutAgainstRealSandbox() throws Exception {
        StripeClient client = StripeClient.builder().setApiKey(apiKey()).setMaxNetworkRetries(1).build();
        StripeConnectGateway connect = new StripeConnectGateway(client);
        UUID tenantId = UUID.randomUUID();

        String account = connect.createAccount(new ConnectGateway.NewAccount(
                tenantId, "Smoke test " + tenantId.toString().substring(0, 8), "smoke@example.com", "CZ", "CZK"));
        assertThat(account).startsWith("acct_");
        System.out.println("[smoke] connected account: " + account);

        String link = connect.createOnboardingLink(account, "https://example.com/refresh", "https://example.com/return");
        assertThat(link).startsWith("https://connect.stripe.com/");
        System.out.println("[smoke] onboarding link: " + link);

        ConnectGateway.AccountStatus status = connect.retrieveStatus(account);
        System.out.println("[smoke] status: " + status);
        assertThat(status.closed()).isFalse();
        assertThat(status.canAcceptPayments()).isFalse(); // nový účet bez onboardingu

        // Platforma (bez připojeného účtu): Checkout Session se musí vytvořit.
        StripeCheckoutGateway checkout = new StripeCheckoutGateway(client);
        CheckoutGateway.CreatedSession platformSession = checkout.createSession(request(null));
        assertThat(platformSession.url()).startsWith("https://checkout.stripe.com/");
        assertThat(checkout.retrieveSession(null, platformSession.id())).isPresent();
        assertThat(checkout.retrieveSession(account, platformSession.id())).isEmpty(); // jiný účet ji nevidí

        // Přímá platba na účet bez dokončeného onboardingu Stripe odmítne – aplikace to ohlásí jako chybu brány.
        try {
            CheckoutGateway.CreatedSession s = checkout.createSession(request(account));
            System.out.println("[smoke] direct charge session created on not-yet-active account: " + s.id());
        } catch (PaymentProviderException e) {
            System.out.println("[smoke] direct charge on inactive account rejected as expected: " + e.getMessage());
        }
    }

    private static CheckoutGateway.SessionRequest request(String account) {
        return new CheckoutGateway.SessionRequest(
                UUID.randomUUID(), "SMOKE001", UUID.randomUUID(), account, account == null ? 0 : 100, "CZK_500", 50000,
                "CZK", "smoke@example.com", "Smoke voucher", "Test", "Smoke voucher",
                "https://example.com/ok?session_id={CHECKOUT_SESSION_ID}", "https://example.com/cancel",
                Instant.now().plusSeconds(3600));
    }
}
