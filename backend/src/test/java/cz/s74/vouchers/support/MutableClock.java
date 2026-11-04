package cz.s74.vouchers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** Testovací hodiny: výchozí je reálný čas, test může čas zafixovat a posouvat. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant fixed;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    public void advance(java.time.Duration duration) {
        this.fixed = instant().plus(duration);
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }
}
