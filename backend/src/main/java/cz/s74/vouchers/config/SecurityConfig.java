package cz.s74.vouchers.config;

import cz.s74.vouchers.audit.AuditService;
import cz.s74.vouchers.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self';"
            + " frame-ancestors 'none'; form-action 'self'";

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

    /** CORS jen pro veřejné API a jen pro přesné originy z konfigurace. CORS není autorizace. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(S74Properties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/public/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            SessionRegistry sessionRegistry,
            RateLimits limits,
            AuditService audit)
            throws Exception {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // Po přihlášení zpět na původní adresu (QR → /staff/v/{kód}); bez parametru "continue".
        requestCache.setMatchingRequestParameterName(null);
        requestCache.setRequestMatcher(request -> HttpMethod.GET.matches(request.getMethod()));

        http.cors(c -> c.configurationSource(cors))
                .csrf(c -> c.ignoringRequestMatchers("/api/public/**", "/api/stripe/webhook"))
                .authorizeHttpRequests(a -> a.requestMatchers("/api/public/**", "/api/stripe/webhook", "/login")
                        .permitAll()
                        .requestMatchers("/app.css", "/staff-ui.css", "/fonts/**", "/error", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/staff/**")
                        .hasAnyRole("STAFF", "MANAGER")
                        .requestMatchers("/admin/**")
                        .hasRole("MANAGER")
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
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
                .addFilterBefore(new LoginProtectionFilter(limits, audit), UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
            delegate.onAuthenticationSuccess(request, response, auth);
        };
    }

    private AuthenticationFailureHandler failureHandler(RateLimits limits, AuditService audit) {
        return (request, response, exception) -> {
            String username = LoginProtectionFilter.normalize(request.getParameter("username"));
            limits.loginFailuresPerUser.hit(username);
            limits.loginFailuresPerIp.hit(request.getRemoteAddr());
            audit.record(
                    AuditService.Actor.SYSTEM, null, "LOGIN_FAILED", null, null, request.getRemoteAddr(),
                    Map.of("username", username));
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
    }
}
