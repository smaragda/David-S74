package cz.s74.vouchers.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Limity z kap. 14.4 analýzy. */
@Component
public class RateLimits {

    public final SlidingWindowLimiter checkoutPerIp;
    public final SlidingWindowLimiter orderStatusPerIp;
    public final SlidingWindowLimiter loginFailuresPerUser;
    public final SlidingWindowLimiter loginFailuresPerIp;
    public final SlidingWindowLimiter lookupMissesPerAccount;
    public final SlidingWindowLimiter lookupMissesPerIp;

    public RateLimits(Clock clock) {
        this.checkoutPerIp = new SlidingWindowLimiter(10, Duration.ofHours(1), clock);
        this.orderStatusPerIp = new SlidingWindowLimiter(60, Duration.ofMinutes(10), clock);
        this.loginFailuresPerUser = new SlidingWindowLimiter(5, Duration.ofMinutes(15), clock);
        this.loginFailuresPerIp = new SlidingWindowLimiter(20, Duration.ofMinutes(15), clock);
        this.lookupMissesPerAccount = new SlidingWindowLimiter(10, Duration.ofMinutes(15), clock);
        this.lookupMissesPerIp = new SlidingWindowLimiter(30, Duration.ofMinutes(15), clock);
    }

    @Scheduled(fixedDelay = 600_000)
    void cleanup() {
        checkoutPerIp.cleanup();
        orderStatusPerIp.cleanup();
        loginFailuresPerUser.cleanup();
        loginFailuresPerIp.cleanup();
        lookupMissesPerAccount.cleanup();
        lookupMissesPerIp.cleanup();
    }

    /** Pro testy: vynuluje všechna počítadla. */
    public void resetAll() {
        for (SlidingWindowLimiter l : new SlidingWindowLimiter[] {
            checkoutPerIp, orderStatusPerIp, loginFailuresPerUser, loginFailuresPerIp,
            lookupMissesPerAccount, lookupMissesPerIp
        }) {
            l.clear();
        }
    }
}
