package cz.s74.vouchers.stripe;

import com.stripe.StripeClient;
import cz.s74.vouchers.config.S74Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StripeConfig {

    /** Jedna instance klienta; nikdy ne globální {@code Stripe.apiKey}. */
    @Bean
    public StripeClient stripeClient(S74Properties props) {
        return StripeClient.builder()
                .setApiKey(props.stripe().apiKey())
                .setMaxNetworkRetries(2)
                .setConnectTimeout(10_000)
                .setReadTimeout(20_000)
                .build();
    }
}
