package com.acme;

import com.acme.audit.AuditService;
import com.acme.auth.AuthService;
import com.acme.auth.PasswordHasher;
import com.acme.auth.TokenAuthenticator;
import com.acme.auth.TokenService;
import com.acme.config.AppConfig;
import com.acme.projects.ProjectService;
import com.acme.repos.InMemoryAuditStore;
import com.acme.repos.InMemoryProjectStore;
import com.acme.repos.InMemoryRefreshTokenStore;
import com.acme.repos.InMemoryTimeEntryStore;
import com.acme.repos.InMemorySettingsStore;
import com.acme.repos.InMemoryUserStore;
import com.acme.timelog.TimeService;
import com.acme.users.UserService;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The whole service wired with in-memory stores and a controllable clock, called through the Lambda entry point
 * with events shaped like a function URL's.
 */
class TestApp {
    static final String ADMIN_EMAIL = "admin@example.com";
    static final String PASSWORD = "correct-horse-1";

    final MutableClock clock = new MutableClock();
    final InMemoryUserStore users = new InMemoryUserStore();
    final InMemoryRefreshTokenStore refreshTokens = new InMemoryRefreshTokenStore();
    final Handler handler;

    TestApp() {
        this(new InMemoryProjectStore());
    }

    TestApp(InMemoryProjectStore projects) {
        AuditService audit = new AuditService(new InMemoryAuditStore());
        InMemoryTimeEntryStore timeEntries = new InMemoryTimeEntryStore(projects);
        // The lowest BCrypt cost keeps the tests fast; production uses 10.
        PasswordHasher passwords = new PasswordHasher(4);
        TokenService tokens = new TokenService(new InMemorySettingsStore(), refreshTokens, clock);
        AppConfig config = new AppConfig();
        handler = new Handler(config.router(new TokenAuthenticator(tokens, users),
                new AuthService(users, passwords, tokens, audit),
                new UserService(users, projects, passwords, tokens, audit),
                new ProjectService(projects, users, timeEntries, audit, clock),
                new TimeService(timeEntries, projects, users, audit, clock), audit));
    }

    /** Sends a request; a path may carry a query string, and a null token sends no Authorization header. */
    Map<String, Object> send(String method, String path, String body, String token) {
        Map<String, Object> event = new HashMap<>();
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            Map<String, String> query = new HashMap<>();
            for (String pair : path.substring(queryStart + 1).split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
            event.put("queryStringParameters", query);
            path = path.substring(0, queryStart);
        }
        event.put("rawPath", path);
        event.put("requestContext", Map.of("http", Map.of("method", method, "path", path)));
        if (token != null) {
            event.put("headers", Map.of("authorization", "Bearer " + token));
        }
        if (body != null) {
            event.put("body", body);
            event.put("isBase64Encoded", false);
        }
        return handler.handleRequest(event, null);
    }

    /** Creates the first admin and returns an access token for them. */
    String setUpAdmin() {
        assertEquals(201, send("POST", "/auth/setup", credentials(ADMIN_EMAIL, PASSWORD, "Ada Admin"), null)
                .get("statusCode"));
        return signIn(ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
    }

    JsonObject signIn(String email, String password) {
        Map<String, Object> response = send("POST", "/auth/login",
                "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}", null);
        assertEquals(200, response.get("statusCode"), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject();
    }

    /** Creates a user as the given admin and returns its id. */
    String createUser(String adminToken, String email, String role, String managerId) {
        String manager = managerId == null ? "" : ", \"managerId\": \"" + managerId + "\"";
        Map<String, Object> response = send("POST", "/users", "{\"email\": \"" + email + "\", \"name\": \"" + email
                + "\", \"role\": \"" + role + "\", \"password\": \"" + PASSWORD + "\"" + manager + "}", adminToken);
        assertEquals(201, response.get("statusCode"), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    static String credentials(String email, String password, String name) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\", \"name\": \"" + name + "\"}";
    }

    static JsonElement body(Map<String, Object> response) {
        return new Gson().fromJson((String) response.get("body"), JsonElement.class);
    }

    static String error(Map<String, Object> response) {
        return body(response).getAsJsonObject().get("error").getAsString();
    }

    static int status(Map<String, Object> response) {
        return (int) response.get("statusCode");
    }

    /** A clock the tests can move forward to expire tokens. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
