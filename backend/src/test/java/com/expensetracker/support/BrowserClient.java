package com.expensetracker.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real cookie/CSRF HTTP requests, shared by authentication and scope tests. */
public class BrowserClient {
    private final int port;
    private final ObjectMapper json;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    private String token;
    private String headerName = "X-CSRF-TOKEN";

    public BrowserClient(int port, ObjectMapper json) { this.port = port; this.json = json; }
    public HttpResponse<String> request(String method, String path, Object body, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (withCsrf && token != null) request.header(headerName, token);
        var payload = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, false); }
    public HttpResponse<String> post(String path, Object body) throws Exception { return request("POST", path, body, true); }
    public JsonNode json(HttpResponse<String> response) { return json.readTree(response.body()); }
    public String csrf() throws Exception {
        var response = get("/api/v1/auth/csrf/");
        if (response.statusCode() != 200) throw new AssertionError("CSRF status " + response.statusCode());
        var data = json(response);
        token = data.get("token").asText(); headerName = data.get("headerName").asText();
        return token;
    }
    public JsonNode signup(String email, String password) throws Exception {
        csrf();
        var response = post("/api/v1/auth/signup/", Map.of("email",email,"displayName","Synthetic user","password",password));
        if (response.statusCode() != 201) throw new AssertionError("Signup status " + response.statusCode());
        return json(response);
    }
    public HttpResponse<String> login(String email, String password) throws Exception {
        csrf(); return post("/api/v1/auth/login/", Map.of("email",email,"password",password));
    }
    public String cookieHeader() {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SESSION"))
                .map(cookie -> cookie.getName() + "=" + cookie.getValue()).findFirst().orElse("");
    }
    public CookieManager cookies() { return cookies; }
}
