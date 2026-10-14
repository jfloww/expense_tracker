package com.expensetracker.foundation;

import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.security.SessionAuthenticationAdapter;
import com.expensetracker.mfa.application.MfaService;
import com.expensetracker.mfa.domain.Totp;
import com.expensetracker.support.BrowserClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationHttpIT extends FoundationTestSupport {
    private static final String PASSWORD = "Synthetic-password-2026";
    private static final String NEW_PASSWORD = "Different-synthetic-password-2026";
    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired private MfaService mfa;
    @Autowired private JdbcIndexedSessionRepository sessionRepository;

    @Test void signupRequiresCsrfValidatesInputsAndDoesNotLogInImplicitly() throws Exception {
        var browser = browser();
        var body = Map.of("email","synthetic@example.test","displayName","Synthetic user","password",PASSWORD);
        status(403, browser.request("POST","/api/v1/auth/signup/",body,false));
        browser.csrf();
        var shortPassword = Map.of("email","synthetic@example.test","displayName","Synthetic user","password","short");
        status(400, browser.post("/api/v1/auth/signup/",shortPassword));
        var created = browser.post("/api/v1/auth/signup/",body);
        status(201, created);
        assertFalse(created.body().contains(PASSWORD));
        assertFalse(created.body().contains("passwordHash"));
        assertEquals("synthetic@example.test",browser.json(created).path("email").asText());
        status(401,browser.get("/api/v1/auth/me/"));
        status(409,browser.post("/api/v1/auth/signup/",body));
        assertEquals(1,users.count());
    }

    @Test void loginRotatesCookiePersistsInPostgresAndRefreshesCsrf() throws Exception {
        var user = register();
        var browser = user.browser();
        String anonymousCookie = browser.cookieHeader();
        var signedIn = browser.login(user.email(),PASSWORD);
        status(200,signedIn);
        String authenticatedCookie = browser.cookieHeader();
        assertNotEquals(anonymousCookie,authenticatedCookie);
        String cookie = signedIn.headers().allValues("Set-Cookie").toString();
        assertTrue(cookie.contains("HttpOnly"),cookie);
        assertTrue(cookie.contains("SameSite=Lax"),cookie);
        status(401,withCookie("/api/v1/auth/me/",anonymousCookie));
        var me = browser.get("/api/v1/auth/me/");
        status(200,me);
        assertEquals(user.id().toString(),browser.json(me).path("id").asText());
        assertPrivate(me);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Integer.class,user.id().toString()));
        // The pre-login token must no longer authorize mutations.
        status(403,browser.post("/api/v1/auth/logout/",Map.of()));
        browser.csrf();
        status(204,browser.post("/api/v1/auth/logout/",Map.of()));
        status(401,withCookie("/api/v1/auth/me/",authenticatedCookie));
        status(401,browser.get("/api/v1/auth/me/"));
    }

    @Test void credentialsAndDisabledAccountsUseTheSameGenericFailure() throws Exception {
        var user = register();
        var browser = user.browser();
        var wrong = browser.login(user.email(),"Wrong-synthetic-password");
        var absent = browser.login("absent@example.test",PASSWORD);
        jdbc.update("UPDATE app_users SET enabled = false WHERE id = ?",user.id());
        var disabled = browser.login(user.email(),PASSWORD);
        status(401,wrong); status(401,absent); status(401,disabled);
        assertEquals(wrong.body(),absent.body()); assertEquals(wrong.body(),disabled.body());
        assertEquals("invalid_credentials",browser.json(wrong).path("code").asText());
        status(401,browser.get("/api/v1/auth/me/"));
    }

    @Test void passwordChangeRequiresPasswordAndRevokesOtherSessionsAndOldPassword() throws Exception {
        var user = register();
        var current = user.browser(); var other = browser();
        status(200,current.login(user.email(),PASSWORD)); current.csrf();
        status(200,other.login(user.email(),PASSWORD)); other.csrf();
        status(401,current.post("/api/v1/auth/password/change/",
                Map.of("currentPassword","wrong","newPassword",NEW_PASSWORD)));
        status(200,other.get("/api/v1/auth/me/"));
        String oldCookie = current.cookieHeader();
        status(204,current.post("/api/v1/auth/password/change/",
                Map.of("currentPassword",PASSWORD,"newPassword",NEW_PASSWORD)));
        status(200,current.get("/api/v1/auth/me/"));
        status(401,other.get("/api/v1/auth/me/"));
        status(401,withCookie("/api/v1/auth/me/",oldCookie));
        var newLogin = browser();
        status(401,newLogin.login(user.email(),PASSWORD));
        status(200,newLogin.login(user.email(),NEW_PASSWORD));
        // Old CSRF was also rotated with the authenticated session.
        status(403,current.post("/api/v1/auth/logout/",Map.of()));
    }

    @Test void usersCanInspectAndRevokeTheirOwnOpaqueSessionsWithoutExposingCookieSecrets() throws Exception {
        var user = register(); var second = browser(); var stranger = register();
        status(200,user.browser().login(user.email(),PASSWORD)); user.browser().csrf();
        status(200,second.login(user.email(),PASSWORD)); second.csrf();
        status(200,stranger.browser().login(stranger.email(),PASSWORD)); stranger.browser().csrf();
        var listed = user.browser().get("/api/v1/auth/sessions/"); status(200,listed);
        JsonNode sessions = user.browser().json(listed); assertEquals(2,sessions.size());
        String otherId = null;
        for (JsonNode session : sessions) {
            assertDoesNotThrow(() -> UUID.fromString(session.path("id").asText()));
            assertFalse(listed.body().contains(cookieSessionId(user.browser())));
            if (!session.path("current").asBoolean()) otherId = session.path("id").asText();
        }
        assertNotNull(otherId);
        status(404,stranger.browser().request("DELETE","/api/v1/auth/sessions/"+otherId+"/",null,true));
        status(200,second.get("/api/v1/auth/me/"));
        status(204,user.browser().request("DELETE","/api/v1/auth/sessions/"+otherId+"/",null,true));
        status(401,second.get("/api/v1/auth/me/"));
        status(200,user.browser().get("/api/v1/auth/me/"));
    }

    @Test void revokeOthersRequiresReauthenticationAndKeepsCurrentSession() throws Exception {
        var user = register(); var other = browser();
        status(200,user.browser().login(user.email(),PASSWORD)); user.browser().csrf();
        status(200,other.login(user.email(),PASSWORD));
        status(401,user.browser().post("/api/v1/auth/sessions/revoke-others/",Map.of("currentPassword","wrong")));
        status(200,other.get("/api/v1/auth/me/"));
        status(204,user.browser().post("/api/v1/auth/sessions/revoke-others/",Map.of("currentPassword",PASSWORD)));
        status(401,other.get("/api/v1/auth/me/"));
        status(200,user.browser().get("/api/v1/auth/me/"));
    }

    @Test void idleAndAbsoluteSessionExpirationAreEnforced() throws Exception {
        var user = register();
        status(200,user.browser().login(user.email(),PASSWORD));
        jdbc.update("UPDATE spring_session SET creation_time = ? WHERE principal_name = ?",
                clock.millis()-13*60*60*1000L,user.id().toString());
        status(401,user.browser().get("/api/v1/auth/me/"));
        status(200,user.browser().login(user.email(),PASSWORD));
        // Session expiration is derived from last access plus idle interval; keep both columns consistent.
        jdbc.update("UPDATE spring_session SET last_access_time = ?, expiry_time = ? WHERE principal_name = ?",
                clock.millis()-31*60*1000L,clock.millis()-60*1000L,user.id().toString());
        status(401,user.browser().get("/api/v1/auth/me/"));
    }

    @Test void mfaSetupLoginRecoveryAndDisableUseTheFullBrowserFlow() throws Exception {
        var user = register(); var oldSession = browser();
        status(200,user.browser().login(user.email(),PASSWORD)); user.browser().csrf();
        status(200,oldSession.login(user.email(),PASSWORD));
        var setup = user.browser().post("/api/v1/auth/mfa/setup/",Map.of("currentPassword",PASSWORD)); status(200,setup);
        var enrollment = user.browser().json(setup); String secret = enrollment.path("secret").asText();
        assertTrue(secret.length()>=32); assertPrivate(setup);
        assertFalse(user.browser().json(user.browser().get("/api/v1/auth/mfa/")).path("enabled").asBoolean());
        String code = Totp.codeAt(secret,clock.instant());
        var confirmed = user.browser().post("/api/v1/auth/mfa/confirm/",Map.of("code",code)); status(200,confirmed);
        var recoveryCodes = user.browser().json(confirmed).path("recoveryCodes"); assertEquals(10,recoveryCodes.size());
        status(401,oldSession.get("/api/v1/auth/me/"));
        status(200,user.browser().get("/api/v1/auth/me/")); user.browser().csrf();
        var newBrowser = browser(); var challenge = newBrowser.login(user.email(),PASSWORD); status(202,challenge);
        assertTrue(newBrowser.json(challenge).path("mfaRequired").asBoolean());
        assertTrue(newBrowser.json(challenge).path("user").isNull());
        status(401,newBrowser.get("/api/v1/auth/me/"));
        status(403,newBrowser.request("POST","/api/v1/auth/login/mfa/",Map.of("code",recoveryCodes.get(0).asText()),false));
        newBrowser.csrf();
        // Confirmation consumed this time-step, so using it again is denied.
        status(401,newBrowser.post("/api/v1/auth/login/mfa/",Map.of("code",code)));
        status(200,newBrowser.post("/api/v1/auth/login/mfa/",Map.of("code",recoveryCodes.get(0).asText())));
        status(200,newBrowser.get("/api/v1/auth/me/")); newBrowser.csrf();
        assertEquals(9,newBrowser.json(newBrowser.get("/api/v1/auth/mfa/")).path("recoveryCodesRemaining").asInt());
        status(401,newBrowser.post("/api/v1/auth/mfa/disable/",Map.of("currentPassword","wrong","code",recoveryCodes.get(1).asText())));
        status(204,newBrowser.post("/api/v1/auth/mfa/disable/",Map.of("currentPassword",PASSWORD,"code",recoveryCodes.get(1).asText())));
        status(200,newBrowser.get("/api/v1/auth/me/"));
        assertFalse(newBrowser.json(newBrowser.get("/api/v1/auth/mfa/")).path("enabled").asBoolean());
        status(401,user.browser().get("/api/v1/auth/me/"));
        status(200,browser().login(user.email(),PASSWORD));
    }

    @Test void expiredOrVersionChangedMfaChallengesCannotEstablishAuthentication() throws Exception {
        var user = register(); var actor = new Actor(user.id());
        var enrollment = mfa.beginEnrollment(actor,PASSWORD);
        var codes = mfa.confirmEnrollment(actor,Totp.codeAt(enrollment.secret(),clock.instant()));
        var challenge = browser(); status(202,challenge.login(user.email(),PASSWORD)); challenge.csrf();
        Session stored = sessionRepository.findById(cookieSessionId(challenge)); assertNotNull(stored);
        stored.setAttribute(SessionAuthenticationAdapter.class.getName()+".PENDING",
                new SessionAuthenticationAdapter.PendingLogin(user.id(),1,clock.instant().minusSeconds(1)));
        publicSessionRepository().save(stored);
        status(401,challenge.post("/api/v1/auth/login/mfa/",Map.of("code",codes.get(0))));
        status(401,challenge.get("/api/v1/auth/me/"));
        status(202,challenge.login(user.email(),PASSWORD)); challenge.csrf();
        jdbc.update("UPDATE app_users SET auth_version = auth_version+1 WHERE id = ?",user.id());
        status(401,challenge.post("/api/v1/auth/login/mfa/",Map.of("code",codes.get(0))));
        assertEquals(10,mfa.status(actor).recoveryCodesRemaining());
        status(401,challenge.get("/api/v1/auth/me/"));
    }

    @Test void loginAttemptsAreDurableAndReturnRetryAfterWithoutCreatingAnAuthenticatedSession() throws Exception {
        var user = register();
        for (int i=0;i<20;i++) status(401,user.browser().login(user.email(),"Wrong-synthetic-password"));
        var limited = user.browser().login(user.email(),PASSWORD); status(429,limited);
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())>0);
        assertEquals("too_many_attempts",user.browser().json(limited).path("code").asText());
        status(401,user.browser().get("/api/v1/auth/me/"));
        assertFalse(jdbc.queryForList("SELECT bucket_hash FROM auth_attempt_limits",String.class).stream()
                .anyMatch(value -> value.contains(user.email())));
    }

    private BrowserClient browser() { return new BrowserClient(port,json); }
    @SuppressWarnings("unchecked")
    private SessionRepository<Session> publicSessionRepository() {
        return (SessionRepository<Session>) (SessionRepository<?>) sessionRepository;
    }
    private Registered register() throws Exception {
        var browser = browser(); String email="synthetic-"+UUID.randomUUID()+"@example.test";
        var user = browser.signup(email,PASSWORD);
        return new Registered(browser,email,UUID.fromString(user.path("id").asText()));
    }
    private String cookieSessionId(BrowserClient browser) {
        String value=browser.cookieHeader().substring("SESSION=".length()).replace("\"","");
        return new String(Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8);
    }
    private HttpResponse<String> withCookie(String path,String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void status(int expected,HttpResponse<String> response) { assertEquals(expected,response.statusCode(),response.body()); }
    private static void assertPrivate(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(response.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"));
    }
    private record Registered(BrowserClient browser,String email,UUID id) {}
}
