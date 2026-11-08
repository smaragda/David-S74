package cz.s74.vouchers.stripe;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Záznam přijatých Stripe událostí (idempotence a audit); ID události je primární klíč. */
@Repository
public class StripeEventStore {

    public enum Status {
        RECEIVED,
        PROCESSED,
        IGNORED,
        FAILED
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public StripeEventStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true, pokud jde o první doručení (řádek byl vložen). */
    public boolean tryInsert(String eventId, String type, boolean livemode, String account) {
        return jdbc.update(
                        "insert into stripe_event (event_id, type, livemode, status, received_at, account)"
                                + " values (?, ?, ?, 'RECEIVED', ?, ?) on conflict (event_id) do nothing",
                        eventId,
                        type,
                        livemode,
                        Timestamp.from(Instant.now(clock)),
                        account)
                > 0;
    }

    public Optional<Status> status(String eventId) {
        return jdbc.query("select status from stripe_event where event_id = ?", (rs, i) -> Status.valueOf(rs.getString(1)), eventId)
                .stream()
                .findFirst();
    }

    public void mark(String eventId, Status status, String error) {
        jdbc.update(
                "update stripe_event set status = ?, error = ?, processed_at = ? where event_id = ?",
                status.name(),
                error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error),
                Timestamp.from(Instant.now(clock)),
                eventId);
    }
}
