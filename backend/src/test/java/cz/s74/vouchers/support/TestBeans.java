package cz.s74.vouchers.support;

import cz.s74.vouchers.stripe.CheckoutGateway;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(ZoneId.of("Europe/Prague"));
    }

    @Bean
    @Primary
    public FakeCheckoutGateway fakeCheckoutGateway() {
        return new FakeCheckoutGateway();
    }

    @Bean
    @Primary
    public FakeConnectGateway fakeConnectGateway() {
        return new FakeConnectGateway();
    }
}
