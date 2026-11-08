package cz.s74.vouchers.config;

import cz.s74.vouchers.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * CORS jen pro veřejné API a jen pro přesné originy: {@code /api/public/t/{slug}/**} podle nastavení podniku
 * (jeho web s vložitelným skriptem), původní {@code /api/public/**} podle konfigurace (web S74). CORS není autorizace.
 */
public class TenantCorsConfigurationSource implements CorsConfigurationSource {

    private static final String TENANT_PREFIX = "/api/public/t/";

    private final S74Properties props;
    private final TenantService tenants;

    public TenantCorsConfigurationSource(S74Properties props, TenantService tenants) {
        this.props = props;
        this.tenants = tenants;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(TENANT_PREFIX)) {
            String rest = path.substring(TENANT_PREFIX.length());
            int slash = rest.indexOf('/');
            String slug = slash < 0 ? rest : rest.substring(0, slash);
            List<String> origins = tenants.bySlug(slug).map(t -> tenants.settings(t).corsAllowedOrigins()).orElse(List.of());
            return origins.isEmpty() ? null : config(origins);
        }
        if (path.startsWith("/api/public/")) {
            return config(props.corsAllowedOrigins());
        }
        return null;
    }

    private static CorsConfiguration config(List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        return config;
    }
}
