package cz.s74.vouchers.config;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.staff.StaffPrincipal;
import cz.s74.vouchers.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

    /** form-action platí i pro přesměrování po odeslání formuláře (hostovaný nákup → Stripe Checkout, onboarding). */
    static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self';"
            + " frame-ancestors 'none'; form-action 'self' https://checkout.stripe.com https://connect.stripe.com";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** CORS jen pro veřejné API: originy výchozího webu z konfigurace, u podniků z jejich nastavení. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(S74Properties props, TenantService tenants) {
        return new TenantCorsConfigurationSource(props, tenants);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            SessionRegistry sessionRegistry,
            RateLimits limits,
            AuditService audit,
            S74Properties props)
            throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // Po přihlášení zpět na původní adresu (QR → /staff/v/{kód}); bez parametru "continue".
        requestCache.setMatchingRequestParameterName(null);
        requestCache.setRequestMatcher(request -> HttpMethod.GET.matches(request.getMethod()));

        http.cors(c -> c.configurationSource(cors))
                // Hostovaný nákup (/v/{slug}/checkout) jen zakládá platbu za anonymního zákazníka – bez CSRF a bez
                // session cookie (zákazník nedostane žádnou cookie).
                .csrf(c -> c.ignoringRequestMatchers(
                        "/api/public/**", "/api/stripe/**", "/v/*/checkout"))
                .authorizeHttpRequests(a -> a.requestMatchers(
                                "/api/public/**",
                                "/api/stripe/webhook",
                                "/api/stripe/connect-webhook",
                                "/api/stripe/account-events",
                                "/login")
                        .permitAll()
                        .requestMatchers("/v/**")
                        .permitAll()
                        .requestMatchers("/app.css", "/staff-ui.css", "/hosted.css", "/fonts/**", "/error", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/staff/**")
                        .hasAnyRole("STAFF", "MANAGER")
                        .requestMatchers("/admin/**")
                        .hasRole("MANAGER")
                        .requestMatchers("/platform/**")
                        .hasRole("PLATFORM_ADMIN")
                        .anyRequest()
                        .denyAll())
                .formLogin(f -> f.loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(successHandler(limits, audit, requestCache))
                        .failureHandler(failureHandler(limits, audit))
                        .permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout").deleteCookies("S74SESSION"))
                .requestCache(rc -> rc.requestCache(requestCache))
                .sessionManagement(s -> s.maximumSessions(3).sessionRegistry(sessionRegistry).expiredUrl("/login?expired"))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(csp(props)))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
                .addFilterBefore(new LoginProtectionFilter(limits, audit), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Při lokální atrapě Stripe (jen test režim) smí formuláře přesměrovat i na ni. */
    static String csp(S74Properties props) {
        String mock = props.stripe().apiBase();
        if (mock == null || mock.isBlank()) {
            return CSP;
        }
        java.net.URI uri = java.net.URI.create(mock.trim());
        return CSP + " " + uri.getScheme() + "://" + uri.getAuthority();
    }

    private AuthenticationSuccessHandler successHandler(
            RateLimits limits, AuditService audit, HttpSessionRequestCache requestCache) {
        SavedRequestAwareAuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/staff");
        delegate.setRequestCache(requestCache);
        return (HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Authentication auth) -> {
            limits.loginFailuresPerUser.reset(LoginProtectionFilter.normalize(auth.getName()));
            StaffPrincipal principal = (StaffPrincipal) auth.getPrincipal();
            audit.staff(principal.getId(), "LOGIN_SUCCESS", null, null, request.getRemoteAddr(), Map.of());
            if (principal.isPlatformAdmin()) {
                // Správce platformy nemá obsluhu ani vedoucí podniku – vždy na přehled podniků.
                requestCache.removeRequest(request, response);
                response.sendRedirect(request.getContextPath() + "/platform");
                return;
            }
            delegate.onAuthenticationSuccess(request, response, auth);
        };
    }

    private AuthenticationFailureHandler failureHandler(RateLimits limits, AuditService audit) {
        return (request, response, exception) -> {
            String username = LoginProtectionFilter.normalize(request.getParameter("username"));
            limits.loginFailuresPerUser.hit(username);
            limits.loginFailuresPerIp.hit(request.getRemoteAddr());
            audit.record(
                    audit.tenantOfUsername(username), AuditService.Actor.SYSTEM, null, "LOGIN_FAILED", null, null,
                    request.getRemoteAddr(), Map.of("username", username));
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
    }
}
