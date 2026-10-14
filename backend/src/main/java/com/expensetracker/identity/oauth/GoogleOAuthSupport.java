package com.expensetracker.identity.oauth;

import com.expensetracker.identity.api.AuthController.LoginView;
import com.expensetracker.identity.application.AuthenticationThrottle;
import com.expensetracker.identity.security.SessionAuthenticationAdapter;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.shared.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Optional OIDC adapter. Spring verifies JWT/state/nonce/PKCE; local sessions retain no provider tokens. */
@Component
@ConditionalOnProperty(name = "security.google.enabled", havingValue = "true")
public class GoogleOAuthSupport {
    public static final String AUTHORIZATION_PATH = "/oauth2/authorization/google";
    private static final String LINK = GoogleOAuthSupport.class.getName() + ".LINK";
    private final boolean enabled;
    private final ObjectProvider<ClientRegistrationRepository> clients;
    private final GoogleIdentityService identities;
    private final SessionAuthenticationAdapter browser;
    private final Clock clock;
    private final ObjectMapper json;
    private final AuthenticationThrottle throttle;

    public GoogleOAuthSupport(@Value("${security.google.enabled:false}") boolean enabled,
            ObjectProvider<ClientRegistrationRepository> clients, GoogleIdentityService identities,
            SessionAuthenticationAdapter browser, Clock clock, ObjectMapper json, AuthenticationThrottle throttle) {
        this.enabled = enabled; this.clients = clients; this.identities = identities;
        this.browser = browser; this.clock = clock; this.json = json;
        this.throttle = throttle;
    }

    public void configure(HttpSecurity http) throws Exception {
        if (!enabled) return;
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients.getObject(), "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var oidc = new OidcUserService();
        // Signed ID-token claims suffice for identity; do not fetch profile data or retain API grants.
        oidc.setRetrieveUserInfo(ignored -> false);
        http.addFilterBefore(new GoogleAttemptFilter(), OAuth2AuthorizationRequestRedirectFilter.class);
        http.oauth2Login(oauth -> oauth
                .loginPage("/api/v1/auth/login/")
                .securityContextRepository(new NullSecurityContextRepository())
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(new LinkResolver(resolver)))
                .authorizedClientRepository(new DiscardingClients())
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(oidc))
                .successHandler(this::success)
                .failureHandler((request, response, failure) -> fail(request, response, 401, "oauth_login_failed"))
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                    @Override public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                        // Explicit-save configuration otherwise replaces the fluent per-filter repository.
                        // Apply this after shared configuration so only browser.signIn saves local identity.
                        filter.setSecurityContextRepository(new NullSecurityContextRepository());
                        return filter;
                    }
                }));
    }

    /** Called only after the root application service verifies local password and any local MFA factor. */
    public String prepareLink(SessionPrincipal principal, HttpServletRequest request) {
        if (!enabled) throw DomainException.invalid("google_oauth_disabled");
        request.getSession(true).setAttribute(LINK,
                new LinkMarker(principal.userId(), principal.authVersion(), clock.instant().plusSeconds(300), null));
        return AUTHORIZATION_PATH;
    }

    private void success(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
                throw DomainException.unauthenticated("invalid_google_identity");
            }
            var token = oidc.getIdToken();
            var claims = new GoogleClaims(token.getIssuer().toString(), token.getSubject(), token.getEmail(),
                    Boolean.TRUE.equals(token.getEmailVerified()), token.getFullName());
            var session = request.getSession(false);
            LinkMarker marker = session != null && session.getAttribute(LINK) instanceof LinkMarker value ? value : null;
            if (marker != null && (!marker.expiresAt().isAfter(clock.instant()) || marker.state() == null
                    || !same(marker.state(), request.getParameter("state")))) {
                throw DomainException.unauthenticated("oauth_link_expired");
            }
            if (marker != null) session.removeAttribute(LINK);
            var result = marker == null ? identities.login(claims)
                    : identities.link(new SessionPrincipal(marker.userId(), "", marker.authVersion()), claims);
            if (result.mfaRequired()) {
                browser.startGoogleChallenge(result.principal(), request);
                write(response, 202, new LoginView(true, null));
            } else {
                browser.signIn(result.principal(), request, response);
                browser.markGoogle(result.principal(), request);
                write(response, 200, new LoginView(false, result.user()));
            }
        } catch (DomainException failure) {
            int status = failure.kind() == DomainException.Kind.CONFLICT ? 409 : 401;
            fail(request, response, status, failure.code());
        } catch (DataIntegrityViolationException concurrentIdentity) {
            fail(request, response, 409, "oauth_login_conflict");
        } catch (RuntimeException failure) {
            fail(request, response, 500, "oauth_login_failed");
        }
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        // An untrusted top-level GET carries Lax cookies. It must not log out an existing local session.
        // The OAuth filter uses a null repository, so clearing its transient context retains no OIDC principal.
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null && session.getAttribute(LINK) instanceof LinkMarker marker
                && marker.state() != null && same(marker.state(), request.getParameter("state"))) {
            session.removeAttribute(LINK);
        }
        write(response, status, new ErrorView(code));
    }

    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "private, no-store");
        response.getWriter().write(json.writeValueAsString(body));
    }

    private static boolean same(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private class GoogleAttemptFilter extends OncePerRequestFilter {
        @Override protected boolean shouldNotFilter(HttpServletRequest request) {
            return !AUTHORIZATION_PATH.equals(request.getServletPath())
                    && !"/login/oauth2/code/google".equals(request.getServletPath());
        }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            try {
                throttle.consume("google-ip:" + request.getRemoteAddr(), 60, Duration.ofMinutes(15));
                String query = request.getQueryString();
                String code = request.getParameter("code");
                String state = request.getParameter("state");
                if (!"GET".equals(request.getMethod()) || request.getContentLengthLong() > 0
                        || (query != null && query.length() > 4096)
                        || request.getParameterMap().size() > 8
                        || (code != null && code.length() > 2048)
                        || (state != null && state.length() > 512)) {
                    throw DomainException.invalid("invalid_oauth_request");
                }
            } catch (DomainException rejected) {
                int status = rejected.kind() == DomainException.Kind.RATE_LIMITED ? 429 : 400;
                if (status == 429) response.setHeader("Retry-After", Integer.toString(rejected.retryAfterSeconds()));
                write(response, status, new ErrorView(rejected.code()));
                return;
            }
            chain.doFilter(request, response);
        }
    }

    private class LinkResolver implements OAuth2AuthorizationRequestResolver {
        private final OAuth2AuthorizationRequestResolver delegate;
        private LinkResolver(OAuth2AuthorizationRequestResolver delegate) { this.delegate = delegate; }
        @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return bind(request, delegate.resolve(request));
        }
        @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
            return bind(request, delegate.resolve(request, registrationId));
        }
        private OAuth2AuthorizationRequest bind(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
            var session = request.getSession(false);
            if (authorization != null && session != null && session.getAttribute(LINK) instanceof LinkMarker marker) {
                session.setAttribute(LINK, new LinkMarker(marker.userId(), marker.authVersion(),
                        marker.expiresAt(), authorization.getState()));
            }
            return authorization;
        }
    }

    /** Token exchange needs transient tokens; this product never stores or reuses Google API grants. */
    private static class DiscardingClients implements OAuth2AuthorizedClientRepository {
        @Override public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) { return null; }
        @Override public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}
        @Override public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}
    }

    private record LinkMarker(UUID userId, long authVersion, Instant expiresAt, String state) implements Serializable {}
    private record ErrorView(String code) {}
}
