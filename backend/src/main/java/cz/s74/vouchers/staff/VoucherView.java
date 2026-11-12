package cz.s74.vouchers.staff;

import cz.s74.vouchers.common.Formats;
import cz.s74.vouchers.voucher.Voucher;
import cz.s74.vouchers.voucher.VoucherCodes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Pohled na voucher pro obsluhu (maskovaný kód, české popisky, efektivní stav). */
public record VoucherView(
        String code,
        String maskedCode,
        String amount,
        String statusLabel,
        String badgeClass,
        String validUntil,
        String issuedAt,
        String redeemedAtLabel,
        String redeemedByName,
        String note,
        String cancelReason,
        boolean redeemable,
        boolean justRedeemedByMe) {

    public static VoucherView of(
            Voucher v, LocalDate today, Instant now, UUID currentStaffId, String redeemedByName, Formats formats) {
        Voucher.EffectiveStatus status = v.effectiveStatus(today);
        String label;
        String badge;
        switch (status) {
            case ACTIVE -> {
                label = "Platný";
                badge = "badge--ok";
            }
            case REDEEMED -> {
                label = "Uplatněn";
                badge = "badge--muted";
            }
            case EXPIRED -> {
                label = "Vypršel " + formats.date(v.getValidUntil());
                badge = "badge--warn";
            }
            default -> {
                label = "Zrušen";
                badge = "badge--bad";
            }
        }
        boolean mine = status == Voucher.EffectiveStatus.REDEEMED
                && currentStaffId != null
                && currentStaffId.equals(v.getRedeemedBy())
                && v.getRedeemedAt() != null
                && Duration.between(v.getRedeemedAt(), now).abs().toMinutes() < 10;
        return new VoucherView(
                v.getCode(),
                VoucherCodes.mask(v.getCode()),
                formats.money(v.getAmountMinor()),
                label,
                badge,
                formats.date(v.getValidUntil()),
                formats.dateTime(v.getIssuedAt()),
                v.getRedeemedAt() == null ? null : formats.dateTime(v.getRedeemedAt()),
                redeemedByName,
                v.getRedemptionNote(),
                reason(v.getCancelReason()),
                status == Voucher.EffectiveStatus.ACTIVE,
                mine);
    }

    static String reason(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "REFUND" -> "Vrácení platby";
            case "DISPUTE" -> "Spor o platbu";
            default -> raw;
        };
    }
}
