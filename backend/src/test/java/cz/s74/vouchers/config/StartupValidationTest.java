package cz.s74.vouchers.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class StartupValidationTest {

    @Configuration
    @EnableConfigurationProperties(S74Properties.class)
    static class Config {}

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "s74.public-base-url=http://localhost:8080",
                        "s74.frontend-base-url=http://localhost:8000",
                        "s74.cors-allowed-origins=http://localhost:8000",
                        "s74.zone=Europe/Prague",
                        "s74.voucher.validity-months=12",
                        "s74.voucher.terms-version=2026-11",
                        "s74.voucher.terms-path=/t.html",
                        "s74.voucher.offers[0].id=CZK_500",
                        "s74.voucher.offers[0].amount-czk=500",
                        "s74.mail.from=a@b.cz",
                        "s74.mail.reply-to=c@d.cz");
    }

    @Test
    void startsWithValidTestConfiguration() {
        runner()
                .withPropertyValues("s74.stripe.api-key=sk_test_x", "s74.stripe.webhook-secret=whsec_x")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void failsWithoutStripeKeyAndExplainsWhy() {
        runner().withPropertyValues("s74.stripe.api-key=", "s74.stripe.webhook-secret=whsec_x").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("STRIPE_API_KEY must be set");
        });
    }

    @Test
    void failsWithoutWebhookSecret() {
        runner().withPropertyValues("s74.stripe.api-key=sk_test_x", "s74.stripe.webhook-secret=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("STRIPE_WEBHOOK_SECRET must be set");
        });
    }

    @Test
    void failsWithoutPublicBaseUrl() {
        runner()
                .withPropertyValues(
                        "s74.public-base-url=", "s74.stripe.api-key=sk_test_x", "s74.stripe.webhook-secret=whsec_x")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("S74_PUBLIC_BASE_URL must be set");
                });
    }

    @Test
    void liveKeyWithTestModeIsRejected() {
        runner()
                .withPropertyValues(
                        "s74.stripe.api-key=rk_live_x", "s74.stripe.webhook-secret=whsec_x",
                        "s74.stripe.expected-livemode=false")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("does not match STRIPE_LIVEMODE");
                });
    }

    @Test
    void testKeyWithLiveModeIsRejected() {
        runner()
                .withPropertyValues(
                        "s74.stripe.api-key=sk_test_x", "s74.stripe.webhook-secret=whsec_x",
                        "s74.stripe.expected-livemode=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
