package com.expensetracker.support;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL only. Every test JVM gets a new schema, including external DB runs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "spring.main.banner-mode=off",
                "security.mfa.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "server.servlet.session.cookie.secure=false",
                "app.authentication-throttle.cleanup-delay=PT24H"})
public abstract class PostgresIntegrationTest {
    private static final String SCHEMA = "test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");
    private static PostgreSQLContainer postgres;

    @Autowired private JdbcTemplate database;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url;
        String username;
        String password;
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            if (!EXTERNAL_URL.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("TEST_DATABASE_URL must be a PostgreSQL JDBC URL");
            }
            url = EXTERNAL_URL;
            username = requiredEnvironment("TEST_DATABASE_USERNAME");
            password = requiredEnvironment("TEST_DATABASE_PASSWORD");
        } else {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:16")
                        .withDatabaseName("expense_tracker_test");
                postgres.start();
            }
            url = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        properties.add("spring.datasource.url", () -> scopedUrl);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    protected void cleanDatabase() {
        // Fixed order respects restrictive foreign keys. Never touches the public schema.
        database.update("DELETE FROM " + SCHEMA + ".accounts");
        database.update("DELETE FROM " + SCHEMA + ".memberships");
        database.update("DELETE FROM " + SCHEMA + ".households");
        database.update("DELETE FROM " + SCHEMA + ".mfa_recovery_codes");
        database.update("DELETE FROM " + SCHEMA + ".mfa_credentials");
        database.update("DELETE FROM " + SCHEMA + ".spring_session_attributes");
        database.update("DELETE FROM " + SCHEMA + ".spring_session");
        database.update("DELETE FROM " + SCHEMA + ".auth_attempt_limits");
        database.update("DELETE FROM " + SCHEMA + ".google_identities");
        database.update("DELETE FROM " + SCHEMA + ".app_users");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required with TEST_DATABASE_URL");
        }
        return value;
    }
}
