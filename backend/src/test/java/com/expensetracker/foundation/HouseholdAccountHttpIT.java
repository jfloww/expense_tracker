package com.expensetracker.foundation;

import com.expensetracker.households.domain.Membership;
import com.expensetracker.households.domain.Role;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.support.BrowserClient;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real HTTP cookie authentication, PostgreSQL authorization, and transport contracts. */
class HouseholdAccountHttpIT extends FoundationTestSupport {
    private static final String PASSWORD = "Synthetic-password-2026";
    @Value("${local.server.port}") private int port;
    @Autowired private ObjectMapper json;

    @Test
    void signedInOwnerCreatesHouseholdAndAccountsWithExplicitNullableFields() throws Exception {
        var owner = browser();
        assertEquals(401, new BrowserClient(port, json).get("/api/v1/households/").statusCode());

        var household = createHousehold(owner, "  Shared household  ");
        String householdId = household.path("id").asText();
        assertEquals("Shared household", household.path("name").asText());
        assertEquals("OWNER", household.path("role").asText());
        assertEquals(1, owner.http().json(owner.http().get("/api/v1/households/")).size());
        assertEquals(household, owner.http().json(owner.http().get(householdPath(householdId))));

        var response = owner.http().post(accountsPath(householdId), accountBody("Shared card", null));
        assertStatus(201, response);
        assertNoStore(response);
        var account = owner.http().json(response);
        assertEquals(householdId, account.path("householdId").asText());
        assertTrue(account.has("lastFour"));
        assertTrue(account.path("lastFour").isNull());
        assertTrue(account.path("active").asBoolean());
        assertTrue(response.headers().firstValue("Location").orElse("")
                .endsWith("/accounts/" + account.path("id").asText() + "/"));

        var read = owner.http().get(accountPath(householdId, account.path("id").asText()));
        assertStatus(200, read);
        assertNoStore(read);
        assertEquals(account, owner.http().json(read));
    }

    @Test
    void householdMembersShareWritesAndRevokedMembershipIsImmediatelyDenied() throws Exception {
        var owner = browser();
        var member = browser();
        String householdId = createHousehold(owner, "Shared household").path("id").asText();
        join(member, householdId);

        var householdRead = member.http().get(householdPath(householdId));
        assertStatus(200, householdRead);
        assertEquals("MEMBER", member.http().json(householdRead).path("role").asText());
        var created = member.http().post(accountsPath(householdId), accountBody("Member card", "1234"));
        assertStatus(201, created);
        String accountId = member.http().json(created).path("id").asText();
        String path = accountPath(householdId, accountId);
        assertStatus(200, owner.http().get(path));
        assertStatus(200, member.http().get(accountsPath(householdId)));

        var edit = updateBody("Renamed shared card", "5678", false);
        var update = member.http().request("PUT", path, edit, true);
        assertStatus(200, update);
        assertFalse(member.http().json(update).path("active").asBoolean());
        var preserved = owner.http().json(owner.http().get(path));

        households.removeMember(new Actor(owner.userId()), UUID.fromString(householdId), member.userId());

        assertNotFound(member.http(), member.http().get(householdPath(householdId)));
        assertNotFound(member.http(), member.http().get(accountsPath(householdId)));
        assertNotFound(member.http(), member.http().get(path));
        assertNotFound(member.http(), member.http().post(accountsPath(householdId), accountBody("Denied card", null)));
        assertNotFound(member.http(), member.http().request("PUT", path, edit, true));
        assertEquals(0, member.http().json(member.http().get("/api/v1/households/")).size());
        assertEquals(preserved, owner.http().json(owner.http().get(path)));
    }

    @Test
    void foreignAndUnknownHouseholdsHaveEquivalentReadAndWriteErrors() throws Exception {
        var owner = browser();
        var stranger = browser();
        String householdId = createHousehold(owner, "Owner household").path("id").asText();
        var created = owner.http().post(accountsPath(householdId), accountBody("Protected card", "1234"));
        assertStatus(201, created);
        var original = owner.http().json(created);
        String accountId = original.path("id").asText();

        for (String scope : new String[]{householdId, UUID.randomUUID().toString()}) {
            assertNotFound(stranger.http(), stranger.http().get(householdPath(scope)));
            assertNotFound(stranger.http(), stranger.http().get(accountsPath(scope)));
            assertNotFound(stranger.http(), stranger.http().get(accountPath(scope, accountId)));
            assertNotFound(stranger.http(), stranger.http().post(accountsPath(scope), accountBody("Denied card", null)));
            assertNotFound(stranger.http(), stranger.http().request("PUT", accountPath(scope, accountId),
                    updateBody("Denied edit", null, false), true));
        }
        assertEquals(original, owner.http().json(owner.http().get(accountPath(householdId, accountId))));
        assertEquals(1, owner.http().json(owner.http().get(accountsPath(householdId))).size());
    }

    @Test
    void membershipInBothHouseholdsDoesNotAllowAccountIdSubstitution() throws Exception {
        var owner = browser();
        String firstId = createHousehold(owner, "First household").path("id").asText();
        String secondId = createHousehold(owner, "Second household").path("id").asText();
        var created = owner.http().post(accountsPath(secondId), accountBody("Second household card", "1234"));
        assertStatus(201, created);
        var original = owner.http().json(created);
        String accountId = original.path("id").asText();

        assertNotFound(owner.http(), owner.http().get(accountPath(firstId, accountId)));
        assertNotFound(owner.http(), owner.http().request("PUT", accountPath(firstId, accountId),
                updateBody("Mis-scoped edit", null, false), true));
        assertNotFound(owner.http(), owner.http().get(accountPath(secondId, UUID.randomUUID().toString())));
        assertEquals(original, owner.http().json(owner.http().get(accountPath(secondId, accountId))));
        assertEquals(0, owner.http().json(owner.http().get(accountsPath(firstId))).size());
    }

    @Test
    void accountPutRequiresActiveAndExplicitNullClearsLastFour() throws Exception {
        var owner = browser();
        String householdId = createHousehold(owner, "Household").path("id").asText();
        var created = owner.http().post(accountsPath(householdId), accountBody("Card", "1234"));
        assertStatus(201, created);
        var original = owner.http().json(created);
        String path = accountPath(householdId, original.path("id").asText());

        assertStatus(400, owner.http().request("PUT", path, accountBody("Missing active", null), true));
        var nullActive = accountBody("Null active", null);
        nullActive.put("active", null);
        assertStatus(400, owner.http().request("PUT", path, nullActive, true));
        var unsupportedCurrency = updateBody("Unsupported currency", null, false);
        unsupportedCurrency.put("currency", "EUR");
        assertStatus(400, owner.http().request("PUT", path, unsupportedCurrency, true));
        assertEquals(original, owner.http().json(owner.http().get(path)));

        var replacement = owner.http().request("PUT", path, updateBody("Renamed card", null, true), true);
        assertStatus(200, replacement);
        var updated = owner.http().json(replacement);
        assertTrue(updated.has("lastFour"));
        assertTrue(updated.path("lastFour").isNull());
        assertTrue(updated.path("active").asBoolean());
        assertEquals("Renamed card", updated.path("name").asText());
        assertEquals(original.path("createdAt"), updated.path("createdAt"));
    }

    @Test
    void csrfIsRequiredForSharedWritesAndDisabledUsersLoseExistingSessionAccess() throws Exception {
        var owner = browser();
        String householdId = createHousehold(owner, "Household").path("id").asText();
        var missingCsrf = owner.http().request("POST", accountsPath(householdId), accountBody("Denied card", null), false);
        assertStatus(403, missingCsrf);
        assertEquals(0, owner.http().json(owner.http().get(accountsPath(householdId))).size());

        assertEquals(1, jdbc.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", owner.userId()));

        assertStatus(401, owner.http().get(householdPath(householdId)));
        assertStatus(401, owner.http().get(accountsPath(householdId)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE household_id = ?",
                Integer.class, UUID.fromString(householdId)));
    }

    private Browser browser() throws Exception {
        BrowserClient http = new BrowserClient(port, json);
        String email = "synthetic-" + UUID.randomUUID() + "@example.test";
        JsonNode user = http.signup(email, PASSWORD);
        assertStatus(200, http.login(email, PASSWORD));
        http.csrf();
        return new Browser(http, UUID.fromString(user.path("id").asText()));
    }

    private JsonNode createHousehold(Browser browser, String name) throws Exception {
        var response = browser.http().post("/api/v1/households/", Map.of("name", name));
        assertStatus(201, response);
        return browser.http().json(response);
    }

    private void join(Browser member, String householdId) {
        memberships.saveAndFlush(Membership.create(
                householdRepository.findById(UUID.fromString(householdId)).orElseThrow(),
                users.findById(member.userId()).orElseThrow(), Role.MEMBER));
    }

    private static Map<String, Object> accountBody(String name, String lastFour) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("issuer", "CHASE");
        body.put("kind", "CREDIT_CARD");
        body.put("lastFour", lastFour);
        body.put("currency", "USD");
        return body;
    }

    private static Map<String, Object> updateBody(String name, String lastFour, boolean active) {
        var body = accountBody(name, lastFour);
        body.put("active", active);
        return body;
    }

    private static String householdPath(String householdId) {
        return "/api/v1/households/" + householdId + "/";
    }

    private static String accountsPath(String householdId) {
        return householdPath(householdId) + "accounts/";
    }

    private static String accountPath(String householdId, String accountId) {
        return accountsPath(householdId) + accountId + "/";
    }

    private static void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
    }

    private static void assertNotFound(BrowserClient browser, HttpResponse<String> response) {
        assertStatus(404, response);
        assertEquals("not_found", browser.json(response).path("code").asText());
    }

    private static void assertNoStore(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private record Browser(BrowserClient http, UUID userId) {}
}
