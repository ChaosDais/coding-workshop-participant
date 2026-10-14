package com.acme;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.acme.TestApp.ADMIN_EMAIL;
import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.credentials;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Sign-in, tokens and the per-endpoint permission checks. */
class AuthTest {
    private final TestApp app = new TestApp();

    @Test
    void firstAdminCanOnlyBeCreatedOnce() {
        String token = app.setUpAdmin();

        Map<String, Object> again = app.send("POST", "/auth/setup", credentials("second@example.com", PASSWORD, "B"),
                null);
        assertEquals(409, status(again));
        JsonObject me = body(app.send("GET", "/auth/me", null, token)).getAsJsonObject();
        assertEquals("ADMIN", me.get("role").getAsString());
        assertFalse(me.has("password") || me.has("passwordHash"));
    }

    @Test
    void setupValidatesItsInput() {
        assertEquals("password must be at least 8 characters",
                error(app.send("POST", "/auth/setup", credentials(ADMIN_EMAIL, "short", "A"), null)));
        assertEquals("email must be a valid email address",
                error(app.send("POST", "/auth/setup", credentials("not-an-email", PASSWORD, "A"), null)));
        // Nothing was created by the failed attempts.
        assertEquals(201, status(app.send("POST", "/auth/setup", credentials(ADMIN_EMAIL, PASSWORD, "A"), null)));
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
