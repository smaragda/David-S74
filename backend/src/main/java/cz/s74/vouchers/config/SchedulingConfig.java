package cz.s74.vouchers.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Plánované úlohy (outbox e-mailů, úklid limitů); v testech se vypínají přes {@code s74.scheduling.enabled=false}. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "s74.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
