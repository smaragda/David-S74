package cz.s74.vouchers.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Veškerá konfigurace aplikace pod prefixem {@code s74}. Aplikace nenastartuje s neplatnou konfigurací. */
@Validated
@ConfigurationProperties("s74")
public record S74Properties(
        @NotBlank(message = "S74_PUBLIC_BASE_URL must be set (e.g. https://vouchery.s74.cz)") String publicBaseUrl,
        @NotBlank(message = "S74_FRONTEND_BASE_URL must be set") String frontendBaseUrl,
        @NotEmpty List<String> corsAllowedOrigins,
        @NotNull ZoneId zone,
        @Valid @NotNull Voucher voucher,
        @Valid @NotNull Stripe stripe,
        @Valid @NotNull Mail mail,
        @Valid BootstrapAdmin bootstrapAdmin,
        @Valid BootstrapAdmin bootstrapPlatformAdmin,
        @Valid @DefaultValue Connect connect) {

    public String publicBaseUrlNoSlash() {
        return stripTrailingSlash(publicBaseUrl);
    }

    public String frontendBaseUrlNoSlash() {
        return stripTrailingSlash(frontendBaseUrl);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record Voucher(
            @Min(1) int validityMonths,
            @NotBlank String termsVersion,
            @NotBlank String termsPath,
            @NotEmpty List<@Valid Offer> offers,
            /** Po kolika minutách od zaplacení se voucher na návratové stránce přestane zobrazovat (0 = nikdy nezobrazovat). */
            @DefaultValue("60") @Min(0) int showOnReturnMinutes) {}

    public record Offer(@NotBlank String id, @Min(1) long amountCzk) {
        public long amountMinor() {
            return amountCzk * 100;
        }
    }

    public record Stripe(
            @NotBlank(message = "STRIPE_API_KEY must be set (restricted key rk_test_… for sandbox)") String apiKey,
            @NotBlank(message = "STRIPE_WEBHOOK_SECRET must be set (whsec_… from `stripe listen`)")
                    String webhookSecret,
            boolean expectedLivemode,
            @DefaultValue("30") @Min(30) int checkoutExpiryMinutes,
            /** Secret endpointu pro události připojených účtů (Connect); prázdné = endpoint vypnutý. */
            @DefaultValue("") String connectWebhookSecret,
            /** Secret endpointu pro v2 (thin) události účtů; prázdné = endpoint vypnutý. */
            @DefaultValue("") String accountEventsWebhookSecret,
            /** Jen pro lokální vývoj: adresa atrapy Stripe API (backend/dev/stripe-mock). Prázdné = api.stripe.com. */
            @DefaultValue("") String apiBase) {

        @AssertTrue(message = "S74_STRIPE_API_BASE (Stripe mock) must not be used with STRIPE_LIVEMODE=true")
        public boolean isApiBaseOnlyInTestMode() {
            return !expectedLivemode || apiBase == null || apiBase.isBlank();
        }

        /** Chrání před záměnou testu a ostrého provozu: live klíč jen s STRIPE_LIVEMODE=true a naopak. */
        @AssertTrue(message = "STRIPE_API_KEY does not match STRIPE_LIVEMODE (live keys need STRIPE_LIVEMODE=true,"
                + " test keys need false)")
        public boolean isKeyMatchingLivemode() {
            if (apiKey == null || apiKey.isBlank()) {
                return true; // reported by @NotBlank
            }
            boolean liveKey = apiKey.startsWith("sk_live_") || apiKey.startsWith("rk_live_");
            boolean testKey = apiKey.startsWith("sk_test_") || apiKey.startsWith("rk_test_");
            return expectedLivemode ? liveKey : testKey;
        }
    }

    /**
     * Stripe Connect (více podniků). {@code enabled=false}: zakládání připojených účtů je vypnuté, výchozí podnik
     * platí přes platformní účet jako dřív.
     */
    public record Connect(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("CZ") @NotBlank String country) {}

    public record Mail(
            @NotBlank String from,
            @NotBlank String replyTo,
            @DefaultValue("5") @Min(1) int maxAttempts) {}

    public record BootstrapAdmin(@DefaultValue("") String username, @DefaultValue("") String password) {
        public boolean configured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }
    }
}
