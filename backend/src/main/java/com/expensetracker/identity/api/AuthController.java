package com.expensetracker.identity.api;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.application.AuthenticationService;
import com.expensetracker.identity.application.AuthenticationThrottle;
import com.expensetracker.identity.application.IdentityService;
import com.expensetracker.identity.application.SessionService;
import com.expensetracker.identity.application.UserView;
import com.expensetracker.identity.security.SessionAuthenticationAdapter;
import com.expensetracker.identity.security.SessionPrincipal;
import com.expensetracker.identity.oauth.GoogleOAuthSupport;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.mfa.application.MfaStatus;
import com.expensetracker.mfa.application.Enrollment;
import com.expensetracker.shared.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identities;
    private final AuthenticationService authentication;
    private final SessionAuthenticationAdapter browser;
    private final AuthenticationThrottle throttle;
    private final SessionService sessions;
    private final MfaService mfa;
    private final ObjectProvider<GoogleOAuthSupport> google;

    public AuthController(IdentityService identities, AuthenticationService authentication,
            SessionAuthenticationAdapter browser, AuthenticationThrottle throttle, SessionService sessions, MfaService mfa,
            ObjectProvider<GoogleOAuthSupport> google) {
        this.identities = identities; this.authentication = authentication; this.browser = browser;
        this.throttle = throttle; this.sessions = sessions; this.mfa = mfa;
        this.google = google;
    }

    @GetMapping("/csrf/")
    public CsrfView csrf(CsrfToken token) { return new CsrfView(token.getToken(), token.getHeaderName(), token.getParameterName()); }

    @PostMapping("/signup/")
    public ResponseEntity<UserView> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        throttle.consume("signup-ip:" + request.getRemoteAddr(), 10, Duration.ofHours(1));
        return ResponseEntity.status(201).body(identities.register(body.email(), body.displayName(), body.password()));
    }

    @PostMapping("/login/")
    public ResponseEntity<LoginView> login(@Valid @RequestBody LoginRequest body,
            @AuthenticationPrincipal SessionPrincipal existing, HttpServletRequest request, HttpServletResponse response) {
        if (existing != null) throw DomainException.conflict("already_authenticated");
        throttle.consume("login-ip:" + request.getRemoteAddr(), 100, Duration.ofMinutes(15));
        throttle.consume("login-email:" + body.email().strip().toLowerCase(java.util.Locale.ROOT), 20, Duration.ofMinutes(15));
        var result = authentication.authenticate(body.email(), body.password());
        if (result.mfaRequired()) {
            browser.startChallenge(result.principal(), request);
            return ResponseEntity.status(202).body(new LoginView(true, null));
        }
        browser.signIn(result.principal(), request, response);
        return ResponseEntity.ok(new LoginView(false, result.user()));
    }

    @PostMapping("/login/mfa/")
    public LoginView verifyLogin(@Valid @RequestBody CodeRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var pending = browser.pending(request);
        limitFactor(pending.userId(), request);
        mfa.verifyLogin(pending.userId(), pending.authVersion(), body.code());
        var result = authentication.completeLogin(pending.userId(), pending.authVersion());
        browser.signIn(result.principal(), request, response);
        if (pending.google()) browser.markGoogle(result.principal(), request);
        return new LoginView(false, result.user());
    }

    @GetMapping("/me/")
    public UserView me(@AuthenticationPrincipal SessionPrincipal principal) { return authentication.current(principal); }

    @PostMapping("/logout/")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        browser.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change/")
    public ResponseEntity<Void> password(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request, HttpServletResponse response) {
        limitSensitive(principal, request);
        authentication.changePassword(principal, body.currentPassword(), body.newPassword(), body.code());
        refresh(principal, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/set/")
    public ResponseEntity<Void> setPassword(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody InitialPasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        limitSensitive(principal, request);
        var proof = browser.requireRecentGoogle(principal, request);
        authentication.setInitialPassword(principal, proof, body.newPassword(), body.code());
        refresh(principal, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/oauth/google/link/")
    public GoogleLinkView linkGoogle(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        var support = google.getIfAvailable();
        if (support == null) throw DomainException.notFound();
        limitSensitive(principal, request);
        authentication.requireReauthentication(principal, body.currentPassword(), body.code());
        return new GoogleLinkView(support.prepareLink(principal, request));
    }

    @GetMapping("/sessions/")
    public List<SessionService.SessionView> sessions(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return sessions.list(actor(principal), request.getSession(false).getId());
    }

    @DeleteMapping("/sessions/{id}/")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id,
            HttpServletRequest request, HttpServletResponse response) {
        var current = sessions.list(actor(principal), request.getSession(false).getId()).stream()
                .filter(item -> item.id().equals(id) && item.current()).findAny().isPresent();
        sessions.revoke(actor(principal), principal.authVersion(), id);
        if (current) browser.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/revoke-others/")
    public ResponseEntity<Void> revokeOthers(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        limitSensitive(principal, request);
        authentication.requireReauthentication(principal, body.currentPassword(), body.code());
        sessions.revokeOthers(actor(principal), principal.authVersion(), request.getSession(false).getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mfa/")
    public MfaStatus mfa(@AuthenticationPrincipal SessionPrincipal principal) { return mfa.status(actor(principal)); }

    @PostMapping("/mfa/setup/")
    public Enrollment setup(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        limitSensitive(principal, request);
        return mfa.beginEnrollment(actor(principal), principal.authVersion(), body.currentPassword());
    }

    @PostMapping("/mfa/confirm/")
    public RecoveryCodesView confirm(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        limitFactor(principal.userId(), request);
        var codes = mfa.confirmEnrollment(actor(principal), principal.authVersion(), body.code());
        refresh(principal, request, response);
        return new RecoveryCodesView(codes);
    }

    @PostMapping("/mfa/disable/")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request, HttpServletResponse response) {
        limitSensitive(principal, request);
        mfa.disable(actor(principal), principal.authVersion(), body.currentPassword(), body.code());
        refresh(principal, request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mfa/recovery-codes/regenerate/")
    public RecoveryCodesView regenerate(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody ReauthenticationRequest body, HttpServletRequest request, HttpServletResponse response) {
        limitSensitive(principal, request);
        var codes = mfa.regenerateRecoveryCodes(actor(principal), principal.authVersion(), body.currentPassword(), body.code());
        refresh(principal, request, response);
        return new RecoveryCodesView(codes);
    }

    private void refresh(SessionPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        browser.signIn(authentication.refreshedPrincipal(actor(principal), Math.incrementExact(principal.authVersion())), request, response);
    }
    private static Actor actor(SessionPrincipal principal) { return new Actor(principal.userId()); }
    private void limitFactor(UUID userId, HttpServletRequest request) {
        throttle.consume("mfa-ip:" + request.getRemoteAddr(), 60, Duration.ofMinutes(15));
        throttle.consume("mfa-user:" + userId, 10, Duration.ofMinutes(5));
    }
    private void limitSensitive(SessionPrincipal principal, HttpServletRequest request) {
        limitFactor(principal.userId(), request);
        throttle.consume("security-user:" + principal.userId(), 20, Duration.ofMinutes(15));
    }

    public record CsrfView(String token, String headerName, String parameterName) {}
    public record LoginView(boolean mfaRequired, UserView user) {}
    public record GoogleLinkView(String authorizationPath) {}
    public record RecoveryCodesView(List<String> recoveryCodes) {
        @Override public String toString() { return "RecoveryCodesView[redacted]"; }
    }
    public record SignupRequest(@NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(max=120) String displayName, @NotBlank @Size(min=12,max=72) String password) {
        @Override public String toString() { return "SignupRequest[redacted]"; }
    }
    public record LoginRequest(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record CodeRequest(@NotBlank @Size(max=32) String code) {
        @Override public String toString() { return "CodeRequest[redacted]"; }
    }
    public record ReauthenticationRequest(@NotBlank @Size(max=72) String currentPassword, @Size(max=32) String code) {
        @Override public String toString() { return "ReauthenticationRequest[redacted]"; }
    }
    public record PasswordChangeRequest(@NotBlank @Size(max=72) String currentPassword,
            @NotBlank @Size(min=12,max=72) String newPassword, @Size(max=32) String code) {
        @Override public String toString() { return "PasswordChangeRequest[redacted]"; }
    }
    public record InitialPasswordRequest(@NotBlank @Size(min=12,max=72) String newPassword, @Size(max=32) String code) {
        @Override public String toString() { return "InitialPasswordRequest[redacted]"; }
    }
}
