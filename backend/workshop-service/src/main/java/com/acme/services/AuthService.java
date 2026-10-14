package com.acme.services;

import com.acme.errors.UnauthorizedException;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.UserRepository;

import java.util.Map;

/** Sign-in, token refresh, sign-out, password changes, and creating the premade admin on an empty database. */
public class AuthService {
    /** What a successful sign-in or refresh returns. */
    public record Session(String accessToken, String refreshToken, String tokenType, long expiresIn, User user) {
        Session(String accessToken, String refreshToken, User user) {
            this(accessToken, refreshToken, "Bearer", TokenService.ACCESS_TOKEN_LIFETIME.toSeconds(), user);
        }
    }

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository users;
    private final PasswordHasher passwords;
    private final TokenService tokens;
    private final AuditService auditService;
    private final DefaultAdmin defaultAdmin;

    /**
     * The premade admin account, created on the first sign-in against an empty database. Its password must be
     * changed before the account can do anything else.
     */
    public record DefaultAdmin(String email, String password) {
        public static final String EMAIL = "admin@acme.com";
        public static final String PASSWORD = "ChangeMe123!";

        /** ADMIN_EMAIL and ADMIN_PASSWORD override the documented defaults. */
        public static DefaultAdmin fromEnvironment(Map<String, String> env) {
            String email = env.getOrDefault("ADMIN_EMAIL", "");
            String password = env.getOrDefault("ADMIN_PASSWORD", "");
            return new DefaultAdmin(email.isBlank() ? EMAIL : email.trim().toLowerCase(),
                    password.isBlank() ? PASSWORD : password);
        }
    }

    public AuthService(UserRepository users, PasswordHasher passwords, TokenService tokens, AuditService auditService,
                       DefaultAdmin defaultAdmin) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.auditService = auditService;
        this.defaultAdmin = defaultAdmin;
    }

    /** Creates the first admin; refused once any user exists. */
    /**
     * Creates the premade admin when the database has no accounts at all. It is called only when a sign-in finds
     * no matching account, so it costs one cheap query on a failed sign-in and nothing otherwise.
     */
    private void createDefaultAdminIfNoUsers() {
        if (users.hasAnyUser()) {
            return;
        }
        User admin = new User(null, defaultAdmin.email(), "Administrator", Role.ADMIN, null, null, null, false, true);
        if (users.insertIfEmpty(admin, passwords.hash(defaultAdmin.password()))) {
            auditService.recordAction("default_admin_created", admin.email(), Map.of("userId", admin.id(),
                    "name", admin.name(), "email", admin.email()));
        }
    }

    public Session signIn(String email, String password) {
        String normalized = email == null ? null : email.trim().toLowerCase();
        UserRepository.Credentials credentials = normalized == null ? null : users.findByEmail(normalized).orElse(null);
        if (credentials == null && normalized != null) {
            createDefaultAdminIfNoUsers();
            credentials = users.findByEmail(normalized).orElse(null);
        }
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

    /**
     * Lets anyone change their own name and email; role, manager, job type and salary stay as they are and only
     * an admin can change them.
     */
    public User updateProfile(User user, String name, String email) {
        User updated = new User(user.id(), email, name, user.role(), user.managerId(), user.jobType(), user.salary(),
                false, user.passwordChangeRequired());
        users.update(updated);
        auditService.recordAction("profile_updated", updated.email(), Map.of("userId", user.id(),
                "name", updated.name(), "email", updated.email(), "previousEmail", user.email()));
        return updated;
    }

    /** Changes the caller's own password and signs them out everywhere else. */
    public void changePassword(User user, String currentPassword, String newPassword) {
        String currentHash = users.findByEmail(user.email()).map(UserRepository.Credentials::passwordHash).orElse(null);
        if (!passwords.matches(currentPassword, currentHash)) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        users.setPasswordHash(user.id(), passwords.hash(newPassword));
        tokens.revokeAllRefreshTokens(user.id());
        auditService.recordAction("password_changed", user.email(), Map.of("userId", user.id(), "name", user.name(),
                "email", user.email()));
    }

    private Session startSession(User user) {
        return new Session(tokens.issueAccessToken(user), tokens.issueRefreshToken(user), user);
    }
}
