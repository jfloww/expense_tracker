package com.expensetracker.foundation;

import com.expensetracker.identity.application.AuthenticationThrottle;
import com.expensetracker.identity.oauth.GoogleIdentityService;
import com.expensetracker.mfa.domain.Totp;
import com.expensetracker.support.BrowserClient;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed, loopback-only OIDC provider; no real Google credentials or network access. */
class GoogleOidcHttpIT extends FoundationTestSupport {
    private static final String PASSWORD = "Synthetic-password-2026";
    private static final String CLIENT_ID = "synthetic-google-client";
    private static final String CLIENT_SECRET = "synthetic-google-client-secret";
    private static final MockProvider PROVIDER = new MockProvider();
    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired private AuthenticationThrottle throttle;

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry properties) {
        properties.add("security.google.enabled", () -> true);
        properties.add("security.google.client-id", () -> CLIENT_ID);
        properties.add("security.google.client-secret", () -> CLIENT_SECRET);
        properties.add("security.google.authorization-uri", () -> PROVIDER.url("/authorize"));
        properties.add("security.google.token-uri", () -> PROVIDER.url("/token"));
        properties.add("security.google.jwk-set-uri", () -> PROVIDER.url("/jwks"));
        properties.add("security.google.user-info-uri", () -> PROVIDER.url("/userinfo"));
    }

    @BeforeEach
    void resetProvider() { PROVIDER.reset(); }

    @AfterAll
    static void stopProvider() { PROVIDER.close(); }

    @Test
    void authorizationUsesStateNonceAndS256PkceAndCallbackCreatesOnlyALocalSession() throws Exception {
        var browser = browser();
        var flow = begin(browser);
        assertEquals(CLIENT_ID, flow.get("client_id"));
        assertEquals("code", flow.get("response_type"));
        assertEquals("S256", flow.get("code_challenge_method"));
        assertTrue(flow.get("scope").contains("openid"));
        assertTrue(flow.get("scope").contains("email"));
        assertTrue(flow.get("scope").contains("profile"));
        assertFalse(flow.get("state").isBlank());
        assertFalse(flow.get("nonce").isBlank());
        assertFalse(flow.get("code_challenge").isBlank());
        String oldCookie = browser.cookieHeader();

        var callback = callback(browser, flow, "google-subject", "synthetic-google@example.test", true, "valid");
        assertStatus(200, callback);
        assertFalse(browser.json(callback).path("mfaRequired").asBoolean());
        assertNotEquals(oldCookie, browser.cookieHeader());
        String userId = browser.json(callback).path("user").path("id").asText();
        assertFalse(users.findById(UUID.fromString(userId)).orElseThrow().isPasswordLoginEnabled());
        assertStatus(200, browser.get("/api/v1/auth/me/"));
        assertStatus(200, browser.get("/api/v1/households/"));
        assertFalse(callback.body().contains("access_token"));
        assertFalse(callback.body().contains("id_token"));
        assertEquals(1, PROVIDER.tokens.get());
        assertEquals(0, PROVIDER.userInfo.get());
        assertTrue(jdbc.queryForList("SELECT attribute_name FROM spring_session_attributes", String.class).stream()
                .noneMatch(name -> name.toLowerCase().contains("authorizedclient")));
    }

    @Test
    void googleUserCanSetAnInitialPasswordOnlyAfterGoogleProofAndThenUseLocalLogin() throws Exception {
        var browser = browser();
        var response = callback(browser, begin(browser), "password-subject", "password@example.test", true, "valid");
        assertStatus(200, response);
        String userId = browser.json(response).path("user").path("id").asText();
        assertFalse(users.findById(UUID.fromString(userId)).orElseThrow().isPasswordLoginEnabled());
        var failedLocal = browser();
        assertStatus(401, failedLocal.login("password@example.test", PASSWORD));
        browser.csrf();
        assertStatus(204, browser.post("/api/v1/auth/password/set/", Map.of("newPassword", PASSWORD)));
        assertTrue(users.findById(UUID.fromString(userId)).orElseThrow().isPasswordLoginEnabled());
        var local = browser();
        assertStatus(200, local.login("password@example.test", PASSWORD));
        local.csrf();
        assertStatus(401, local.post("/api/v1/auth/password/set/", Map.of("newPassword", "Other-synthetic-password")));
        assertEquals(userId, local.json(local.get("/api/v1/auth/me/")).path("id").asText());
    }

    @Test
    void matchingEmailRequiresExplicitPasswordStepUpLinkAndRevokesPreviousSessions() throws Exception {
        var local = browser();
        JsonNode user = local.signup("local@example.test", PASSWORD);
        assertStatus(200, local.login("local@example.test", PASSWORD));
        local.csrf();
        var previous = browser();
        assertStatus(200, previous.login("local@example.test", PASSWORD));

        var anonymous = browser();
        var collision = callback(anonymous, begin(anonymous), "local-subject", "local@example.test", true, "valid");
        assertStatus(409, collision);
        assertEquals("oauth_account_link_required", anonymous.json(collision).path("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));
        assertStatus(401, anonymous.get("/api/v1/auth/me/"));

        var withoutCsrf = local.request("POST", "/api/v1/auth/oauth/google/link/", Map.of("currentPassword", PASSWORD), false);
        assertStatus(403, withoutCsrf);
        assertStatus(401, local.post("/api/v1/auth/oauth/google/link/", Map.of("currentPassword", "wrong-password")));
        var prepared = local.post("/api/v1/auth/oauth/google/link/", Map.of("currentPassword", PASSWORD));
        assertStatus(200, prepared);
        assertEquals("/oauth2/authorization/google", local.json(prepared).path("authorizationPath").asText());
        var linked = callback(local, begin(local), "local-subject", "local@example.test", true, "valid");
        assertStatus(200, linked);
        assertEquals(user.path("id").asText(), local.json(linked).path("user").path("id").asText());
        assertStatus(401, previous.get("/api/v1/auth/me/"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));
        assertEquals(1, users.count());
    }

    @Test
    void googleLoginStillRequiresTheLocalMfaFactorBeforeProtectedAccess() throws Exception {
        var browser = browser();
        String subject = "mfa-google-subject";
        var first = callback(browser, begin(browser), subject, "mfa-google@example.test", true, "valid");
        assertStatus(200, first);
        String userId = browser.json(first).path("user").path("id").asText();
        browser.csrf();
        assertStatus(204, browser.post("/api/v1/auth/password/set/", Map.of("newPassword", PASSWORD)));
        browser.csrf();
        var setup = browser.post("/api/v1/auth/mfa/setup/", Map.of("currentPassword", PASSWORD));
        assertStatus(200, setup);
        String secret = browser.json(setup).path("secret").asText();
        var confirmed = browser.post("/api/v1/auth/mfa/confirm/", Map.of("code", Totp.codeAt(secret, clock.instant())));
        assertStatus(200, confirmed);
        String recovery = browser.json(confirmed).path("recoveryCodes").get(0).asText();
        browser.csrf();
        assertStatus(204, browser.post("/api/v1/auth/logout/", Map.of()));

        var gated = callback(browser, begin(browser), subject, "mfa-google@example.test", true, "valid");
        assertStatus(202, gated);
        assertTrue(browser.json(gated).path("mfaRequired").asBoolean());
        assertTrue(browser.json(gated).path("user").isNull());
        assertStatus(401, browser.get("/api/v1/auth/me/"));
        assertStatus(401, browser.get("/api/v1/households/"));
        browser.csrf();
        var complete = browser.post("/api/v1/auth/login/mfa/", Map.of("code", recovery));
        assertStatus(200, complete);
        assertEquals(userId, browser.json(complete).path("user").path("id").asText());
        assertStatus(200, browser.get("/api/v1/households/"));
    }

    @Test
    void invalidStateStopsBeforeTokenExchangeAndInvalidTokensNeverCreateUsers() throws Exception {
        var wrongState = browser();
        var flow = begin(wrongState);
        String code = PROVIDER.issue(flow, "state-subject", "state@example.test", true, "valid");
        assertStatus(401, wrongState.get(callbackPath("invalid-state", code)));
        assertEquals(0, PROVIDER.tokens.get());
        assertStatus(401, wrongState.get("/api/v1/auth/me/"));

        for (String invalid : new String[]{"bad-signature", "bad-nonce", "bad-audience", "bad-issuer", "expired"}) {
            var browser = browser();
            var rejected = callback(browser, begin(browser), "rejected-" + invalid,
                    "rejected@example.test", true, invalid);
            assertStatus(401, rejected);
            assertEquals("oauth_login_failed", browser.json(rejected).path("code").asText());
            assertStatus(401, browser.get("/api/v1/auth/me/"));
            assertFalse(rejected.body().contains("id_token"));
        }
        assertEquals(0, users.count());
    }

    @Test
    void forgedCallbackCannotLogOutALocalUserOrConsumeTheirPreparedLink() throws Exception {
        var local = browser();
        var user = local.signup("protected-local@example.test", PASSWORD);
        assertStatus(200, local.login("protected-local@example.test", PASSWORD));
        local.csrf();
        assertStatus(200, local.post("/api/v1/auth/oauth/google/link/", Map.of("currentPassword", PASSWORD)));
        var validFlow = begin(local);

        var forged = local.get(callbackPath("untrusted-state", "untrusted-code"));
        assertStatus(401, forged);
        assertEquals(0, PROVIDER.tokens.get());
        var stillSignedIn = local.get("/api/v1/auth/me/");
        assertStatus(200, stillSignedIn);
        assertEquals(user.path("id").asText(), local.json(stillSignedIn).path("id").asText());

        var linked = callback(local, validFlow, "protected-local-subject", "protected-local@example.test", true, "valid");
        assertStatus(200, linked);
        assertEquals(user.path("id").asText(), local.json(linked).path("user").path("id").asText());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));
    }

    @Test
    void unverifiedEmailAndLinkEmailMismatchAreDeniedWithoutChangingBindings() throws Exception {
        var unverified = browser();
        assertStatus(401, callback(unverified, begin(unverified), "unverified", "unverified@example.test", false, "valid"));
        assertEquals(0, users.count());

        var local = browser();
        local.signup("owner@example.test", PASSWORD);
        assertStatus(200, local.login("owner@example.test", PASSWORD));
        local.csrf();
        assertStatus(200, local.post("/api/v1/auth/oauth/google/link/", Map.of("currentPassword", PASSWORD)));
        var mismatch = callback(local, begin(local), "different-subject", "different@example.test", true, "valid");
        assertStatus(409, mismatch);
        assertEquals("oauth_email_mismatch", local.json(mismatch).path("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM google_identities", Integer.class));
        assertStatus(200, local.get("/api/v1/auth/me/"));
    }

    @Test
    void googleAttemptLimitRunsBeforeRedirectOrTokenExchangeAndQueriesAreBounded() throws Exception {
        var browser = browser();
        assertStatus(400, browser.get("/login/oauth2/code/google?state=" + "x".repeat(513)));
        for (int attempt = 1; attempt < 60; attempt++) {
            throttle.consume("google-ip:127.0.0.1", 60, Duration.ofMinutes(15));
        }
        var limited = browser.get("/oauth2/authorization/google");
        assertStatus(429, limited);
        assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElse("0")) >= 1);
        assertFalse(limited.headers().firstValue("Location").isPresent());
        assertStatus(429, browser.get("/login/oauth2/code/google?state=synthetic&code=synthetic"));
        assertEquals(0, PROVIDER.tokens.get());
    }

    private BrowserClient browser() { return new BrowserClient(port, json); }

    private Map<String, String> begin(BrowserClient browser) throws Exception {
        var response = browser.get("/oauth2/authorization/google");
        assertStatus(302, response);
        URI redirect = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertEquals(PROVIDER.server.getAddress().getPort(), redirect.getPort());
        return parameters(redirect.getRawQuery());
    }

    private HttpResponse<String> callback(BrowserClient browser, Map<String, String> flow,
            String subject, String email, boolean verified, String variant) throws Exception {
        return browser.get(callbackPath(flow.get("state"), PROVIDER.issue(flow, subject, email, verified, variant)));
    }

    private static String callbackPath(String state, String code) {
        return "/login/oauth2/code/google?state=" + encode(state) + "&code=" + encode(code);
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
        if (expected != 302) {
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static Map<String, String> parameters(String input) {
        var result = new LinkedHashMap<String, String>();
        if (input == null || input.isBlank()) return result;
        for (String pair : input.split("&")) {
            String[] values = pair.split("=", 2);
            result.put(URLDecoder.decode(values[0], StandardCharsets.UTF_8), values.length == 1 ? ""
                    : URLDecoder.decode(values[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static class MockProvider {
        private final HttpServer server;
        private final RSAKey key;
        private final RSAKey incorrectKey;
        private final ObjectMapper json = new ObjectMapper();
        private final Map<String, Grant> grants = new ConcurrentHashMap<>();
        private final AtomicInteger tokens = new AtomicInteger();
        private final AtomicInteger userInfo = new AtomicInteger();

        MockProvider() {
            try {
                key = new RSAKeyGenerator(2048).keyID("synthetic-key").generate();
                incorrectKey = new RSAKeyGenerator(2048).keyID("synthetic-key").generate();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks", exchange -> respond(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
                server.createContext("/token", this::token);
                server.createContext("/userinfo", exchange -> {
                    userInfo.incrementAndGet(); respond(exchange, 400, Map.of("error", "unexpected_userinfo_request"));
                });
                server.start();
            } catch (IOException | JOSEException failed) {
                throw new IllegalStateException("Synthetic OIDC provider could not start", failed);
            }
        }

        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        void reset() { grants.clear(); tokens.set(0); userInfo.set(0); }
        void close() { server.stop(0); }

        String issue(Map<String, String> authorization, String subject, String email, boolean verified, String variant)
                throws JOSEException {
            Instant now = Instant.now();
            boolean expired = "expired".equals(variant);
            var claims = new JWTClaimsSet.Builder().issuer("bad-issuer".equals(variant) ? "https://attacker.example"
                            : GoogleIdentityService.ISSUER)
                    .subject(subject).audience("bad-audience".equals(variant) ? "another-client" : CLIENT_ID)
                    .issueTime(Date.from(now.minusSeconds(expired ? 300 : 1)))
                    .expirationTime(Date.from(now.plusSeconds(expired ? -120 : 300)))
                    .claim("email", email).claim("email_verified", verified).claim("name", "Synthetic Google user")
                    .claim("nonce", "bad-nonce".equals(variant) ? "wrong-nonce" : authorization.get("nonce")).build();
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("synthetic-key").build(), claims);
            token.sign(new RSASSASigner("bad-signature".equals(variant) ? incorrectKey : key));
            String code = UUID.randomUUID().toString();
            grants.put(code, new Grant(token.serialize(), authorization.get("code_challenge"), authorization.get("redirect_uri")));
            return code;
        }

        private void token(HttpExchange exchange) throws IOException {
            tokens.incrementAndGet();
            var body = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Grant grant = grants.remove(body.get("code"));
            String verifier = body.get("code_verifier");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String expectedAuth = "Basic " + Base64.getEncoder()
                    .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
            try {
                String challenge = verifier == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                if (grant == null || !grant.challenge().equals(challenge) || !expectedAuth.equals(auth)
                        || !grant.redirectUri().equals(body.get("redirect_uri"))) {
                    respond(exchange, 400, Map.of("error", "invalid_grant")); return;
                }
                respond(exchange, 200, Map.of("access_token", "synthetic-unused-access-token", "token_type", "Bearer",
                        "expires_in", 300, "scope", "openid email profile", "id_token", grant.token()));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private void respond(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }

        private record Grant(String token, String challenge, String redirectUri) {}
    }
}
