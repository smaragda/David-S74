package cz.s74.vouchers.common;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class EmailAddresses {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private EmailAddresses() {}

    /** Trim, kontrola tvaru (max 254 znaků) a malá písmena v doméně; prázdné, pokud adresa není platná. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String email = raw.trim();
        int at = email.lastIndexOf('@');
        if (email.isEmpty() || email.length() > 254 || at < 1 || !EMAIL.matcher(email).matches()) {
            return Optional.empty();
        }
        try {
            new InternetAddress(email, true).validate();
        } catch (AddressException e) {
            return Optional.empty();
        }
        return Optional.of(email.substring(0, at) + "@" + email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
