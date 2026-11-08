package cz.s74.vouchers.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cz.s74.vouchers.support.AbstractIT;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrencyTest extends AbstractIT {

    @Autowired FulfillmentService fulfillment;

    @Test
    void webhookAndStatusEndpointRacingIssueExactlyOneVoucher() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("truncate audit_log, email_delivery, voucher, voucher_order, stripe_event restart identity cascade");
            checkout("CZK_1000", "race@example.com");
            String sid = gateway.lastSessionId();
            gateway.markPaid(sid);

            int threads = 6;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    switch (n % 3) {
                        case 0 -> sendWebhook("evt_race_" + n, "checkout.session.completed",
                                "{\"id\":\"" + sid + "\"}", false);
                        case 1 -> mvc.perform(get("/api/public/orders/status").param("session_id", sid));
                        default -> fulfillment.fulfill(null, sid);
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            assertThat(count("voucher")).as("round %d", round).isEqualTo(1);
            assertThat(count("email_delivery")).as("round %d", round).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from voucher_order", String.class)).isEqualTo("PAID");
        }
    }
}
