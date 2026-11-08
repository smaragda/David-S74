package cz.s74.vouchers.tenant;

import cz.s74.vouchers.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Určení podniku pro veřejné API a hostované stránky (podle slugu v adrese; bez slugu výchozí podnik). */
@Component
public class PublicTenantResolver {

    private final TenantService tenants;

    public PublicTenantResolver(TenantService tenants) {
        this.tenants = tenants;
    }

    public TenantSettings defaultTenant() {
        return tenants.defaultSettings();
    }

    /** Neznámý slug = 404 (stejně pro neexistující i pozastavený podnik bez nabídky – nic neprozrazuje). */
    public TenantSettings bySlug(String slug) {
        return tenants.bySlug(slug)
                .map(tenants::settings)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Podnik nebyl nalezen."));
    }
}
