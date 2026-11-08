package cz.s74.vouchers.admin;

import cz.s74.vouchers.common.Masking;
import cz.s74.vouchers.config.S74Properties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Měsíční CSV pro účetní: prodané, uplatněné, zrušené / vrácené a vypršelé vouchery (UTF-8 s BOM, středník). */
@Service
public class ExportService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] HEADER = {
        "typ", "datum", "číslo objednávky", "kód (maskovaný)", "hodnota Kč", "Stripe PaymentIntent", "obsluha", "poznámka"
    };

    private final JdbcTemplate jdbc;
    private final ZoneId zone;

    public ExportService(JdbcTemplate jdbc, S74Properties props) {
        this.jdbc = jdbc;
        this.zone = props.zone();
    }

    /** Export jednoho podniku – každý dotaz filtrovaný {@code tenant_id}. */
    public byte[] csv(UUID tenantId, YearMonth month, LocalDate today) {
        Timestamp from = Timestamp.from(month.atDay(1).atStartOfDay(zone).toInstant());
        Timestamp to = Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        List<String[]> rows = new ArrayList<>();
        rows.add(HEADER);

        jdbc.query(
                "select o.paid_at, o.id, v.code, v.amount_minor, o.stripe_payment_intent_id from voucher v"
                        + " join voucher_order o on o.id = v.order_id where o.tenant_id = ? and o.paid_at >= ? and o.paid_at < ? order by o.paid_at",
                rs -> {
                    rows.add(row("PRODANÝ", instant(rs.getTimestamp(1)), ref(rs.getString(2)), Masking.code(rs.getString(3)),
                            rs.getLong(4), rs.getString(5), "", ""));
                },
                tenantId, from, to);
        jdbc.query(
                "select v.redeemed_at, o.id, v.code, v.amount_minor, o.stripe_payment_intent_id, s.display_name,"
                        + " v.redemption_note from voucher v join voucher_order o on o.id = v.order_id"
                        + " left join staff_user s on s.id = v.redeemed_by"
                        + " where v.tenant_id = ? and v.status = 'REDEEMED' and v.redeemed_at >= ? and v.redeemed_at < ? order by v.redeemed_at",
                rs -> {
                    rows.add(row("UPLATNĚNÝ", instant(rs.getTimestamp(1)), ref(rs.getString(2)), Masking.code(rs.getString(3)),
                            rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                },
                tenantId, from, to);
        jdbc.query(
                "select v.cancelled_at, o.id, v.code, v.amount_minor, o.stripe_payment_intent_id, v.cancel_reason"
                        + " from voucher v join voucher_order o on o.id = v.order_id"
                        + " where v.tenant_id = ? and v.status = 'CANCELLED' and v.cancelled_at >= ? and v.cancelled_at < ? order by v.cancelled_at",
                rs -> {
                    rows.add(row("ZRUŠENÝ", instant(rs.getTimestamp(1)), ref(rs.getString(2)), Masking.code(rs.getString(3)),
                            rs.getLong(4), rs.getString(5), "", rs.getString(6)));
                },
                tenantId, from, to);
        jdbc.query(
                "select o.updated_at, o.id, o.amount_minor, o.refunded_amount_minor, o.stripe_payment_intent_id"
                        + " from voucher_order o where o.tenant_id = ? and o.refunded_amount_minor > 0 and o.updated_at >= ? and o.updated_at < ?"
                        + " order by o.updated_at",
                rs -> {
                    rows.add(row("VRÁCENÁ PLATBA", instant(rs.getTimestamp(1)), ref(rs.getString(2)), "", rs.getLong(4),
                            rs.getString(5), "", "vráceno z " + rs.getLong(3) / 100 + " Kč"));
                },
                tenantId, from, to);
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        if (!last.isBefore(today)) {
            last = today.minusDays(1);
        }
        LocalDate lastExpiry = last;
        jdbc.query(
                "select v.valid_until, o.id, v.code, v.amount_minor, o.stripe_payment_intent_id from voucher v"
                        + " join voucher_order o on o.id = v.order_id"
                        + " where v.tenant_id = ? and v.status = 'ACTIVE' and v.valid_until >= ? and v.valid_until <= ? order by v.valid_until",
                rs -> {
                    rows.add(row("VYPRŠELÝ", rs.getDate(1).toString(), ref(rs.getString(2)), Masking.code(rs.getString(3)),
                            rs.getLong(4), rs.getString(5), "", ""));
                },
                tenantId, java.sql.Date.valueOf(first), java.sql.Date.valueOf(lastExpiry));

        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(cell(r[i]));
            }
            sb.append("\r\n");
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    private static String[] row(String type, String date, String ref, String code, long minor, String pi, String staff, String note) {
        return new String[] {type, date, ref, code, Long.toString(minor / 100), pi == null ? "" : pi,
            staff == null ? "" : staff, note == null ? "" : note};
    }

    private String instant(Timestamp ts) {
        return ts == null ? "" : DATE_TIME.format(Instant.ofEpochMilli(ts.getTime()).atZone(zone));
    }

    private static String ref(String uuid) {
        return uuid.substring(0, 8).toUpperCase();
    }

    /** Uvozovky a ochrana proti vzorcům v tabulkových procesorech (=, +, -, @ na začátku buňky). */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+")) {
            v = "'" + v;
        }
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
