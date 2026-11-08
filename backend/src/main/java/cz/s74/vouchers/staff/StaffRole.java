package cz.s74.vouchers.staff;

public enum StaffRole {
    /** Obsluha podniku: ověření a uplatnění voucherů. */
    STAFF,
    /** Vedoucí podniku: zásahy, účty obsluhy, export, Stripe účet podniku. */
    MANAGER,
    /** Správce platformy: zakládá podniky a spravuje jejich připojení ke Stripe, zákaznická data nevidí. */
    PLATFORM_ADMIN
}
