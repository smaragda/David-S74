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
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                .setApiKey(props.stripe().apiKey())
                .setMaxNetworkRetries(2)
                .setConnectTimeout(10_000)
                .setReadTimeout(20_000);
        String apiBase = props.stripe().apiBase();
        if (apiBase != null && !apiBase.isBlank()) {
            builder.setApiBase(apiBase); // lokální atrapa (jen test režim – hlídá S74Properties)
        }
        return builder.build();
    }
}
