package cz.s74.vouchers.config;

import cz.s74.vouchers.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Dočasné zablokování přihlášení po opakovaných neúspěších (podle uživatele i IP) – i pro správné heslo. */
public class LoginProtectionFilter extends OncePerRequestFilter {

    private final RateLimits limits;
    private final AuditService audit;

    public LoginProtectionFilter(RateLimits limits, AuditService audit) {
        this.limits = limits;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && (request.getContextPath() + "/login").equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = normalize(request.getParameter("username"));
        String ip = request.getRemoteAddr();
        if (limits.loginFailuresPerUser.isBlocked(username) || limits.loginFailuresPerIp.isBlocked(ip)) {
            audit.record(audit.tenantOfUsername(username), AuditService.Actor.SYSTEM, null, "LOGIN_LOCKED", null, null, ip,
                    Map.of("username", username));
            response.sendRedirect(request.getContextPath() + "/login?locked");
            return;
        }
        chain.doFilter(request, response);
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
