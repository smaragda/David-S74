package cz.s74.vouchers.voucher;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class VoucherCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 12 znaků, každý 5 bitů z {@link SecureRandom} (32 = mocnina 2, bez modulo biasu). */
    public String generate() {
        StringBuilder sb = new StringBuilder(VoucherCodes.LENGTH);
        for (int i = 0; i < VoucherCodes.LENGTH; i++) {
            sb.append(VoucherCodes.ALPHABET.charAt(random.nextInt(32)));
        }
        return sb.toString();
    }
}
