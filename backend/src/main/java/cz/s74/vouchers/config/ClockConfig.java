package cz.s74.vouchers.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Jediný zdroj času v aplikaci; testy ho nahrazují. Zóna Europe/Prague pro data platnosti. */
    @Bean
    public Clock clock(S74Properties properties) {
        return Clock.system(properties.zone());
    }
}
