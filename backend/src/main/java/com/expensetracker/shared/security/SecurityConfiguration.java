package com.expensetracker.shared.security;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import com.expensetracker.identity.application.AuthenticationService;
import com.expensetracker.identity.security.DatabaseUserDetailsService;
import com.expensetracker.identity.security.SessionValidityFilter;
import com.expensetracker.identity.oauth.GoogleOAuthSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableScheduling
public class SecurityConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean AuthenticationManager authenticationManager(DatabaseUserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName("SESSION");
        cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true);
        cookies.setUseSecureCookie(secure);
        cookies.setSameSite("Lax");
        return cookies;
    }
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrf, AuthenticationService identities, Clock clock,
            ObjectProvider<GoogleOAuthSupport> google) throws Exception {
        var support = google.getIfAvailable();
        if (support != null) support.configure(http);
        return http
            .authorizeHttpRequests(access -> access
                .requestMatchers(HttpMethod.GET, "/api/v1/health/", "/api/v1/auth/csrf/").permitAll()
                .requestMatchers(HttpMethod.GET, "/oauth2/authorization/google", "/login/oauth2/code/google").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup/", "/api/v1/auth/login/",
                        "/api/v1/auth/login/mfa/", "/api/v1/auth/logout/").permitAll()
                .anyRequest().authenticated())
            .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf))
            .requestCache(cache -> cache.disable())
            .addFilterAfter(new SessionValidityFilter(identities, clock), SecurityContextHolderFilter.class)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) ->
                    writeError(response, 401, "unauthenticated"))
                .accessDeniedHandler((request, response, failure) ->
                    writeError(response, 403, "forbidden")))
            .build();
        // Signup, login, challenge completion and logout all remain CSRF-protected.
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "private, no-store");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
