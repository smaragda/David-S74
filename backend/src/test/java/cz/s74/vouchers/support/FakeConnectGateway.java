package cz.s74.vouchers.support;

import cz.s74.vouchers.connect.ConnectGateway;
import cz.s74.vouchers.stripe.PaymentProviderException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Atrapa Stripe Connect: zakládá účty v paměti, test nastavuje jejich stav. */
public class FakeConnectGateway implements ConnectGateway {

    public final List<NewAccount> created = new CopyOnWriteArrayList<>();
    public final List<String> links = new CopyOnWriteArrayList<>();
    private final Map<String, AccountStatus> statuses = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    public volatile boolean fail;

    @Override
    public String createAccount(NewAccount account) {
        if (fail) {
            throw new PaymentProviderException("simulated outage", null);
        }
        created.add(account);
        String id = "acct_fake_" + seq.incrementAndGet();
        statuses.put(id, new AccountStatus(null, 3, false));
        return id;
    }

    @Override
    public String createOnboardingLink(String accountId, String refreshUrl, String returnUrl) {
        if (fail) {
            throw new PaymentProviderException("simulated outage", null);
        }
        String url = "https://connect.stripe.test/setup/" + accountId + "?refresh=" + refreshUrl + "&return=" + returnUrl;
        links.add(url);
        return url;
    }

    @Override
    public AccountStatus retrieveStatus(String accountId) {
        if (fail) {
            throw new PaymentProviderException("simulated outage", null);
        }
        AccountStatus s = statuses.get(accountId);
        if (s == null) {
            throw new PaymentProviderException("no such account", null);
        }
        return s;
    }

    public void setStatus(String accountId, AccountStatus status) {
        statuses.put(accountId, status);
    }

    public void reset() {
        created.clear();
        links.clear();
        statuses.clear();
        fail = false;
    }
}
