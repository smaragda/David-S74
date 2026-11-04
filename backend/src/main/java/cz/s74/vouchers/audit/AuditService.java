package cz.s74.vouchers.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Auditní záznamy. Do {@code details} nikdy nepatří hesla, tajné klíče ani celý voucherový kód.
 * Zápis se připojí k transakci volajícího (stejný DataSource), mimo transakci se provede samostatně.
 */
@Service
public class AuditService {

    public enum Actor {
        STAFF,
        SYSTEM,
        STRIPE
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void record(
            Actor actor,
            UUID actorId,
            String action,
            UUID voucherId,
            UUID orderId,
            String ip,
            Map<String, ?> details) {
        String detailsJson = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
        jdbc.update(
                "insert into audit_log (occurred_at, actor_type, actor_id, action, voucher_id, order_id, ip, details)"
                        + " values (?, ?, ?, ?, ?, ?, cast(cast(? as varchar) as inet), cast(cast(? as varchar) as jsonb))",
                Timestamp.from(Instant.now(clock)),
                actor.name(),
                actorId,
                action,
                voucherId,
                orderId,
                ip,
                detailsJson);
    }

    public void system(String action, UUID voucherId, UUID orderId, Map<String, ?> details) {
        record(Actor.SYSTEM, null, action, voucherId, orderId, null, details);
    }

    public void stripe(String action, UUID voucherId, UUID orderId, Map<String, ?> details) {
        record(Actor.STRIPE, null, action, voucherId, orderId, null, details);
    }

    public void staff(
            UUID staffId, String action, UUID voucherId, UUID orderId, String ip, Map<String, ?> details) {
        record(Actor.STAFF, staffId, action, voucherId, orderId, ip, details);
    }

    public List<AuditEntry> forVoucher(UUID voucherId, UUID orderId) {
        return jdbc.query(
                "select a.occurred_at, a.actor_type, s.display_name, a.action, cast(a.details as varchar)"
                        + " from audit_log a left join staff_user s on s.id = a.actor_id"
                        + " where a.voucher_id = ? or a.order_id = ? order by a.occurred_at, a.id",
                (rs, i) -> new AuditEntry(
                        rs.getTimestamp(1).toInstant(),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5)),
                voucherId,
                orderId);
    }

    public record AuditEntry(Instant at, String actorType, String actorName, String action, String details) {}
}
