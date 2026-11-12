package cz.s74.vouchers.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory klouzavé okno počítadel podle klíče (jedna instance aplikace, takže stačí). */
public class SlidingWindowLimiter {

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** Zaznamená událost, pokud je ještě místo. @return false, pokud je limit vyčerpán. */
    public boolean tryAcquire(String key) {
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            evict(deque);
            if (deque.size() >= max) {
                return false;
            }
            deque.addLast(Instant.now(clock));
            return true;
        }
    }

    /** Je klíč zablokován (počet událostí v okně dosáhl limitu)? Nic nezaznamenává. */
    public boolean isBlocked(String key) {
        Deque<Instant> deque = hits.get(key);
        if (deque == null) {
            return false;
        }
        synchronized (deque) {
            evict(deque);
            return deque.size() >= max;
        }
    }

    /** Zaznamená událost bez ohledu na limit (např. neúspěšné přihlášení). */
    public void hit(String key) {
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            evict(deque);
            deque.addLast(Instant.now(clock));
        }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    public void clear() {
        hits.clear();
    }

    /** Úklid prázdných klíčů, aby mapa nerostla. */
    public void cleanup() {
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                evict(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }

    private void evict(Deque<Instant> deque) {
        Instant threshold = Instant.now(clock).minus(window);
        Iterator<Instant> it = deque.iterator();
        while (it.hasNext() && it.next().isBefore(threshold)) {
            it.remove();
        }
    }
}
