package cz.s74.vouchers.common;

/** Formátování částek pro texty. Peníze se všude jinde drží v haléřích ({@code long}). */
public final class Money {

    private Money() {}

    /** 100000 haléřů → {@code 1 000 Kč} (pevná mezera jako oddělovač tisíců). */
    public static String czkFromMinor(long amountMinor) {
        return czk(amountMinor / 100);
    }

    public static String czk(long amountCzk) {
        String digits = Long.toString(Math.abs(amountCzk));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (amountCzk < 0 ? "-" : "") + sb + " Kč";
    }
}
