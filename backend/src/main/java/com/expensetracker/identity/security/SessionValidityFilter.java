package com.expensetracker.identity.security;

import com.expensetracker.identity.application.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidityFilter extends OncePerRequestFilter {
    public static final long ABSOLUTE_SECONDS = 12 * 60 * 60;
    private final AuthenticationService identities;
    private final Clock clock;
    public SessionValidityFilter(AuthenticationService identities, Clock clock) {
        this.identities = identities; this.clock = clock;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            var session = request.getSession(false);
            boolean valid = authentication.getPrincipal() instanceof SessionPrincipal principal
                    && session != null && session.getCreationTime() + ABSOLUTE_SECONDS * 1000 > clock.millis()
                    && identities.sessionIsCurrent(principal);
            if (!valid) {
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                // A browser with a revoked cookie can bootstrap fresh anonymous CSRF state.
                if ("GET".equals(request.getMethod()) && ("/api/v1/auth/csrf/".equals(request.getServletPath())
                        || "/api/v1/health/".equals(request.getServletPath()))) {
                    chain.doFilter(request, response);
                    return;
                }
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"unauthenticated\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
