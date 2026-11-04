package cz.s74.vouchers.voucher;

import java.util.Locale;
import java.util.Optional;

/** Voucherový kód: Crockford Base32, 12 znaků, kanonicky bez pomlček. */
public final class VoucherCodes {

    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int LENGTH = 12;

    private VoucherCodes() {}

    /**
     * Normalizace ručně zadaného kódu: trim, odstranění mezer / pomlček / teček, velká písmena,
     * Crockford mapování O→0, I→1, L→1. Prázdný výsledek znamená neplatný tvar (délka ≠ 12 nebo
     * znak mimo abecedu).
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        for (char raw : input.trim().toCharArray()) {
            if (Character.isWhitespace(raw) || raw == '-' || raw == '.' || raw == '‐' || raw == '–') {
                continue;
            }
            char c = Character.toUpperCase(raw);
            c = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
            if (ALPHABET.indexOf(c) < 0) {
                return Optional.empty();
            }
            sb.append(c);
        }
        return sb.length() == LENGTH ? Optional.of(sb.toString().toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** {@code ABCD1234EFGH} → {@code ABCD-1234-EFGH}. */
    public static String format(String canonical) {
        return canonical.substring(0, 4) + "-" + canonical.substring(4, 8) + "-" + canonical.substring(8);
    }

    /** {@code ABCD1234EFGH} → {@code ****-****-EFGH}. */
    public static String mask(String canonical) {
        if (canonical == null || canonical.length() < 4) {
            return "****-****-****";
        }
        return "****-****-" + canonical.substring(canonical.length() - 4);
    }
}
