package com.expensetracker.foundation;

import com.expensetracker.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityBoundaryIT extends PostgresIntegrationTest {
    @Value("${local.server.port}") private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void livenessIsPublicAndReturnsJson() throws Exception {
        var response = send("GET", "/api/v1/health/");

        assertEquals(200, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"status\""));
    }

    @Test
    void nonpublicApiRequestsRequireAuthenticationWithJsonError() throws Exception {
        var response = send("GET", "/api/v1/protected");

        assertEquals(401, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"code\""));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test
    void browserMutationWithoutCsrfTokenIsDenied() throws Exception {
        var response = send("POST", "/api/v1/protected");

        assertEquals(403, response.statusCode());
        assertJson(response);
        assertTrue(response.body().contains("\"code\""));
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertJson(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().startsWith("{"));
    }
}
