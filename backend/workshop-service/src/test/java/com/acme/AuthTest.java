package com.acme;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.acme.TestApp.ADMIN_EMAIL;
import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sign-in, tokens and the per-endpoint permission checks. */
class AuthTest {
    private final TestApp app = new TestApp();

    @Test
    void thePremadeAdminIsCreatedOnFirstSignInAndMustChangeItsPassword() {
        JsonObject session = app.signIn(ADMIN_EMAIL, TestApp.INITIAL_ADMIN_PASSWORD);
        JsonObject admin = session.getAsJsonObject("user");
        assertEquals("ADMIN", admin.get("role").getAsString());
        assertTrue(admin.get("passwordChangeRequired").getAsBoolean());
        assertFalse(admin.has("password") || admin.has("passwordHash"));
        String token = session.get("accessToken").getAsString();

        // Until the password is changed, only the account's own routes work.
        assertEquals(200, status(app.send("GET", "/auth/me", null, token)));
        Map<String, Object> blocked = app.send("GET", "/projects", null, token);
        assertEquals(403, status(blocked));
        assertEquals("Change your password before continuing", error(blocked));
        assertEquals(403, status(app.send("POST", "/users", "{}", token)));
        assertEquals(403, status(app.send("PUT", "/auth/me", "{\"name\": \"A\", \"email\": \"a@example.com\"}",
                token)));

        assertEquals(204, status(app.send("POST", "/auth/password", "{\"currentPassword\": \""
                + TestApp.INITIAL_ADMIN_PASSWORD + "\", \"newPassword\": \"" + PASSWORD + "\"}", token)));
        // The same token works once the change is done, because the account is reloaded on every request.
        assertEquals(200, status(app.send("GET", "/projects", null, token)));
        assertFalse(body(app.send("GET", "/auth/me", null, token)).getAsJsonObject()
                .get("passwordChangeRequired").getAsBoolean());
        assertEquals(401, status(app.send("POST", "/auth/login", "{\"email\": \"" + ADMIN_EMAIL
                + "\", \"password\": \"" + TestApp.INITIAL_ADMIN_PASSWORD + "\"}", null)));
    }

    @Test
    void thePremadeAdminIsOnlyCreatedWhileThereAreNoAccounts() {
        String token = app.setUpAdmin();
        String premadeId = body(app.send("GET", "/auth/me", null, token)).getAsJsonObject().get("id").getAsString();
        app.createUser(token, "second-admin@example.com", "ADMIN", null);
        String secondToken = app.signIn("second-admin@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(204, status(app.send("DELETE", "/users/" + premadeId, null, secondToken)));
        // Archiving does not delete, so the database is not empty and no second premade admin appears.
        assertEquals(401, status(app.send("POST", "/auth/login", "{\"email\": \"" + ADMIN_EMAIL
                + "\", \"password\": \"" + TestApp.INITIAL_ADMIN_PASSWORD + "\"}", null)));
        assertEquals(2, app.users.findAll(false).size() + app.users.findAll(true).size());
        // The setup endpoint it replaces is gone.
        assertEquals(404, status(app.send("POST", "/auth/setup", "{}", null)));
    }

    @Test
    void signInFailuresAllLookTheSame() {
        app.setUpAdmin();
        String wrongPassword = error(app.send("POST", "/auth/login",
                "{\"email\": \"" + ADMIN_EMAIL + "\", \"password\": \"wrong-password\"}", null));
        String unknownEmail = error(app.send("POST", "/auth/login",
                "{\"email\": \"nobody@example.com\", \"password\": \"" + PASSWORD + "\"}", null));
        assertEquals("Invalid email or password", wrongPassword);
        assertEquals(wrongPassword, unknownEmail);
        // Email matching ignores case.
        app.signIn(ADMIN_EMAIL.toUpperCase(), PASSWORD);
    }

    @Test
    void missingInvalidAndExpiredTokensAreRejected() {
        String token = app.setUpAdmin();

        assertEquals("Authentication required", error(app.send("GET", "/projects", null, null)));
        assertEquals(401, status(app.send("GET", "/projects", null, null)));
        assertEquals("Invalid or expired token", error(app.send("GET", "/projects", null, "not.a.token")));
        assertEquals("Invalid or expired token", error(app.send("GET", "/projects", null, token + "x")));

        app.clock.advance(Duration.ofMinutes(14));
        assertEquals(200, status(app.send("GET", "/projects", null, token)));
        app.clock.advance(Duration.ofMinutes(2));
        assertEquals("Invalid or expired token", error(app.send("GET", "/projects", null, token)));
    }

    @Test
    void refreshTokensRotateAndAReusedOneEndsEverySession() {
        app.setUpAdmin();
        JsonObject session = app.signIn(ADMIN_EMAIL, PASSWORD);
        String firstRefresh = session.get("refreshToken").getAsString();
        assertEquals("Bearer", session.get("tokenType").getAsString());
        assertEquals(900, session.get("expiresIn").getAsInt());

        // An expired access token can be renewed with the refresh token.
        app.clock.advance(Duration.ofMinutes(20));
        Map<String, Object> refreshed = app.send("POST", "/auth/refresh", refresh(firstRefresh), null);
        assertEquals(200, status(refreshed));
        String secondRefresh = body(refreshed).getAsJsonObject().get("refreshToken").getAsString();
        assertNotEquals(firstRefresh, secondRefresh);
        String newAccess = body(refreshed).getAsJsonObject().get("accessToken").getAsString();
        assertEquals(200, status(app.send("GET", "/projects", null, newAccess)));

        // Using the first refresh token again looks like theft, so the newer one is revoked too.
        assertEquals(401, status(app.send("POST", "/auth/refresh", refresh(firstRefresh), null)));
        assertEquals(401, status(app.send("POST", "/auth/refresh", refresh(secondRefresh), null)));
    }

    @Test
    void refreshTokensExpireAndSignOutRevokesThem() {
        app.setUpAdmin();
        String refreshToken = app.signIn(ADMIN_EMAIL, PASSWORD).get("refreshToken").getAsString();
        assertEquals(204, status(app.send("POST", "/auth/logout", refresh(refreshToken), null)));
        assertEquals(401, status(app.send("POST", "/auth/refresh", refresh(refreshToken), null)));

        String later = app.signIn(ADMIN_EMAIL, PASSWORD).get("refreshToken").getAsString();
        app.clock.advance(Duration.ofDays(8));
        assertEquals("Invalid or expired refresh token",
                error(app.send("POST", "/auth/refresh", refresh(later), null)));
        assertEquals(401, status(app.send("POST", "/auth/refresh", "{}", null)));
    }

    @Test
    void changingYourPasswordNeedsTheCurrentOneAndEndsOtherSessions() {
        String token = app.setUpAdmin();
        String otherSession = app.signIn(ADMIN_EMAIL, PASSWORD).get("refreshToken").getAsString();

        assertEquals("Current password is incorrect", error(app.send("POST", "/auth/password",
                "{\"currentPassword\": \"wrong-password\", \"newPassword\": \"new-password-1\"}", token)));
        assertEquals(204, status(app.send("POST", "/auth/password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"new-password-1\"}", token)));

        assertEquals(401, status(app.send("POST", "/auth/refresh", refresh(otherSession), null)));
        assertEquals(401, status(app.send("POST", "/auth/login",
                "{\"email\": \"" + ADMIN_EMAIL + "\", \"password\": \"" + PASSWORD + "\"}", null)));
        app.signIn(ADMIN_EMAIL, "new-password-1");
    }

    private static String refresh(String token) {
        return "{\"refreshToken\": \"" + token + "\"}";
    }
}
