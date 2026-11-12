package cz.s74.vouchers.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VoucherCodesTest {

    private final VoucherCodeGenerator generator = new VoucherCodeGenerator();

    @Test
    void generatesCodesOfFixedLengthFromCrockfordAlphabet() {
        for (int i = 0; i < 1000; i++) {
            String code = generator.generate();
            assertThat(code).hasSize(12);
            assertThat(code).matches("[0-9A-HJKMNP-TV-Z]{12}");
        }
    }

    @Test
    void hundredThousandCodesDoNotCollide() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }

    @Test
    void normalizeToleratesSpacesCaseAndSeparators() {
        assertThat(VoucherCodes.normalize("abcd-1234-efgh")).contains("ABCD1234EFGH");
        assertThat(VoucherCodes.normalize("  ABCD 1234  EFGH ")).contains("ABCD1234EFGH");
        assertThat(VoucherCodes.normalize("abcd.1234.efgh")).contains("ABCD1234EFGH");
        assertThat(VoucherCodes.normalize("ABCD1234EFGH")).contains("ABCD1234EFGH");
    }

    @Test
    void normalizeMapsAmbiguousCharacters() {
        // O→0, I→1, L→1 (Crockford)
        assertThat(VoucherCodes.normalize("OIL0-1111-2222")).contains("011011112222");
        assertThat(VoucherCodes.normalize("oil0-1111-2222")).contains("011011112222");
    }

    @Test
    void normalizeRejectsWrongLengthOrInvalidCharacters() {
        assertThat(VoucherCodes.normalize("ABCD-1234")).isEmpty();
        assertThat(VoucherCodes.normalize("ABCD-1234-EFGH-J")).isEmpty();
        assertThat(VoucherCodes.normalize("ABCD-1234-EFG!")).isEmpty();
        assertThat(VoucherCodes.normalize("ABCD-1234-EFGU")).isEmpty(); // U není v abecedě
        assertThat(VoucherCodes.normalize("")).isEmpty();
        assertThat(VoucherCodes.normalize(null)).isEqualTo(Optional.empty());
    }

    @Test
    void formatAndMask() {
        assertThat(VoucherCodes.format("ABCD1234EFGH")).isEqualTo("ABCD-1234-EFGH");
        assertThat(VoucherCodes.mask("ABCD1234EFGH")).isEqualTo("****-****-EFGH");
    }
}
