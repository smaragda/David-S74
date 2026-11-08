package cz.s74.vouchers.tenant;

import java.util.UUID;

/** Konstanty k tenantům. */
public final class Tenants {

    /** Výchozí tenant S74 – vlastník všech dat z doby před zavedením více podniků (migrace V2). */
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Tenants() {}
}
