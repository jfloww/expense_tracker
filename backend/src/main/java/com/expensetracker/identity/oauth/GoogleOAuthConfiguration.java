package com.expensetracker.identity.oauth;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "security.google.enabled", havingValue = "true")
public class GoogleOAuthConfiguration {
    @Bean
    ClientRegistrationRepository googleClientRegistrations(
            @Value("${security.google.client-id:}") String clientId,
            @Value("${security.google.client-secret:}") String clientSecret,
            @Value("${security.google.redirect-uri:{baseUrl}/login/oauth2/code/google}") String redirectUri,
            @Value("${security.google.authorization-uri:https://accounts.google.com/o/oauth2/v2/auth}") String authorizationUri,
            @Value("${security.google.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            @Value("${security.google.jwk-set-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwkSetUri,
            @Value("${security.google.user-info-uri:https://openidconnect.googleapis.com/v1/userinfo}") String userInfoUri) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Google OAuth requires a client ID and client secret");
        }
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                .clientId(clientId).clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri).scope("openid", "email", "profile")
                .authorizationUri(endpoint(authorizationUri)).tokenUri(endpoint(tokenUri))
                .jwkSetUri(endpoint(jwkSetUri)).userInfoUri(endpoint(userInfoUri))
                .userNameAttributeName("sub").issuerUri(GoogleIdentityService.ISSUER)
                .clientName("Google").build());
    }

    private static String endpoint(String value) {
        URI uri = URI.create(value);
        boolean localHttp = "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if (value.length() > 2048 || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getFragment() != null || !("https".equals(uri.getScheme()) || localHttp)) {
            throw new IllegalArgumentException("Google OAuth endpoints require HTTPS or a local synthetic provider");
        }
        return value;
    }
}
