package com.acme.auth;

import com.acme.audit.AuditService;
import com.acme.errors.ConflictException;
import com.acme.errors.UnauthorizedException;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.UserStore;

import java.util.Map;

/** Sign-in, token refresh, sign-out, password changes and the one-time creation of the first admin. */
public class AuthService {
    /** What a successful sign-in or refresh returns. */
    public record Session(String accessToken, String refreshToken, String tokenType, long expiresIn, User user) {
        Session(String accessToken, String refreshToken, User user) {
            this(accessToken, refreshToken, "Bearer", TokenService.ACCESS_TOKEN_LIFETIME.toSeconds(), user);
        }
    }

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserStore users;
    private final PasswordHasher passwords;
    private final TokenService tokens;
    private final AuditService auditService;

    public AuthService(UserStore users, PasswordHasher passwords, TokenService tokens, AuditService auditService) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.auditService = auditService;
    }

    /** Creates the first admin; refused once any user exists. */
    public User setUpFirstAdmin(String email, String name, String password) {
        User admin = new User(null, email, name, Role.ADMIN, null, false);
        if (!users.insertIfEmpty(admin, passwords.hash(password))) {
            throw new ConflictException("Setup is already complete; sign in as an admin to add users");
        }
        auditService.recordAction("first_admin_created", admin.email(), Map.of("userId", admin.id()));
        return admin;
    }

    public Session signIn(String email, String password) {
        UserStore.Credentials credentials = email == null ? null
                : users.findByEmail(email.trim().toLowerCase()).orElse(null);
        // The same message for an unknown email, a wrong password and an archived account reveals nothing.
        boolean valid = passwords.matches(password, credentials == null ? null : credentials.passwordHash());
        if (!valid || credentials.user().archived()) {
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        return startSession(credentials.user());
    }

    /** Swaps a refresh token for a new access and refresh token; the old refresh token stops working. */
    public Session refresh(String refreshToken) {
        String userId = tokens.redeemRefreshToken(refreshToken);
        User user = users.findById(userId)
                .filter(found -> !found.archived())
                .orElseThrow(() -> new UnauthorizedException("Account is not active"));
        return startSession(user);
    }

    public void signOut(String refreshToken) {
        tokens.revokeRefreshToken(refreshToken);
    }

    /** Changes the caller's own password and signs them out everywhere else. */
    public void changePassword(User user, String currentPassword, String newPassword) {
        String currentHash = users.findByEmail(user.email()).map(UserStore.Credentials::passwordHash).orElse(null);
        if (!passwords.matches(currentPassword, currentHash)) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        users.setPasswordHash(user.id(), passwords.hash(newPassword));
        tokens.revokeAllRefreshTokens(user.id());
        auditService.recordAction("password_changed", user.email(), Map.of("userId", user.id()));
    }

    private Session startSession(User user) {
        return new Session(tokens.issueAccessToken(user), tokens.issueRefreshToken(user), user);
    }
}
