package com.expensetracker.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import static org.junit.jupiter.api.Assertions.*;

class SecurityCookieTest {
    @Test void productionCookieIsSecureHttpOnlyLaxAndHasNoDomainScope() {
        var response = new MockHttpServletResponse();
        new SecurityConfiguration().cookieSerializer(true).writeCookieValue(
                new CookieValue(new MockHttpServletRequest(),response,"synthetic-session"));
        var cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie); assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/")); assertFalse(cookie.contains("Domain="));
    }
    @Test void localHttpOptOutOnlyRemovesTheSecureFlag() {
        var response = new MockHttpServletResponse();
        new SecurityConfiguration().cookieSerializer(false).writeCookieValue(
                new CookieValue(new MockHttpServletRequest(),response,"synthetic-session"));
        var cookie = response.getHeader("Set-Cookie");
        assertFalse(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
    }
}
