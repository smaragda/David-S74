package cz.s74.vouchers.support;

import cz.s74.vouchers.stripe.CheckoutGateway;
import cz.s74.vouchers.stripe.PaymentProviderException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Atrapa Stripe: drží si vytvořené session a umožňuje testu "zaplatit" je. */
public class FakeCheckoutGateway implements CheckoutGateway {

    public final List<SessionRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, SessionSnapshot> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> paymentIntentOrders = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    public volatile boolean failCreate;
    public volatile boolean failRetrieve;

    @Override
    public CreatedSession createSession(SessionRequest request) {
        if (failCreate) {
            throw new PaymentProviderException("simulated outage", null);
        }
        requests.add(request);
        String id = "cs_test_fake_" + seq.incrementAndGet();
        sessions.put(
                id,
                new SessionSnapshot(
                        id, "payment", request.currency().toLowerCase(), request.amountMinor(), "unpaid", "open", null,
                        request.orderId().toString()));
        return new CreatedSession(id, "https://checkout.stripe.test/c/pay/" + id);
    }

    @Override
    public Optional<SessionSnapshot> retrieveSession(String sessionId) {
        if (failRetrieve) {
            throw new PaymentProviderException("simulated outage", null);
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<String> orderIdOfPaymentIntent(String paymentIntentId) {
        return Optional.ofNullable(paymentIntentOrders.get(paymentIntentId));
    }

    public String lastSessionId() {
        return "cs_test_fake_" + seq.get();
    }

    public SessionRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public void markPaid(String sessionId) {
        SessionSnapshot s = sessions.get(sessionId);
        String pi = "pi_fake_" + sessionId;
        paymentIntentOrders.put(pi, s.orderId());
        sessions.put(sessionId, new SessionSnapshot(
                s.id(), s.mode(), s.currency(), s.amountTotal(), "paid", "complete", pi, s.orderId()));
    }

    /** Odložená platba: session dokončena, peníze ještě nedorazily. */
    public void markCompleteUnpaid(String sessionId) {
        SessionSnapshot s = sessions.get(sessionId);
        sessions.put(sessionId, new SessionSnapshot(
                s.id(), s.mode(), s.currency(), s.amountTotal(), "unpaid", "complete", null, s.orderId()));
    }

    public void override(SessionSnapshot snapshot) {
        sessions.put(snapshot.id(), snapshot);
    }

    public String paymentIntentOf(String sessionId) {
        return sessions.get(sessionId).paymentIntentId();
    }

    public void reset() {
        requests.clear();
        sessions.clear();
        paymentIntentOrders.clear();
        failCreate = false;
        failRetrieve = false;
    }
}
