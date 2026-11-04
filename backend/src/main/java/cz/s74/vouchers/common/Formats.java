package cz.s74.vouchers.common;

import cz.s74.vouchers.config.S74Properties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** České formáty data, času a částek pro šablony (dostupné jako {@code @formats}). */
@Component("formats")
public class Formats {

    private static final Locale CS = Locale.forLanguageTag("cs");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d. M. yyyy", CS);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d. M. yyyy H:mm", CS);

    private final S74Properties props;

    public Formats(S74Properties props) {
        this.props = props;
    }

    public String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    public String dateTime(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant.atZone(props.zone()));
    }

    public String money(long amountMinor) {
        return Money.czkFromMinor(amountMinor);
    }
}
