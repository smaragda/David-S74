package cz.s74.vouchers.common;

import cz.s74.vouchers.voucher.VoucherCodes;

/** Maskování osobních údajů a voucherových kódů pro logy a veřejné odpovědi. */
public final class Masking {

    private Masking() {}

    /** {@code jana@example.com} → {@code j***@e***.com}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.lastIndexOf('@');
        if (at < 1) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String domainName = dot > 0 ? domain.substring(0, dot) : domain;
        String tld = dot > 0 ? domain.substring(dot) : "";
        return first(local) + "***@" + first(domainName) + "***" + tld;
    }

    /** Kód nikdy celý: {@code ****-****-AB12}. */
    public static String code(String canonicalCode) {
        return VoucherCodes.mask(canonicalCode);
    }

    private static String first(String s) {
        return s.isEmpty() ? "" : s.substring(0, 1);
    }
}
