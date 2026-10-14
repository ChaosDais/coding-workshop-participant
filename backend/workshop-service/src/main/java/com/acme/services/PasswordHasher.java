package com.acme.services;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

/** BCrypt password hashing plus the password rules. */
public class PasswordHasher {
    static final int MIN_LENGTH = 8;
    /** BCrypt ignores everything past 72 bytes, so longer passwords are refused rather than silently cut. */
    static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;
    /** Compared against when the email is unknown, so a failed sign-in takes as long either way. */
    private final String dummyHash;

    /** @param strength BCrypt cost: 10 in production; tests use the minimum, 4, to stay fast. */
    public PasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    /** Validates and hashes a new password. */
    public String hash(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("password must be at most " + MAX_BYTES + " bytes");
        }
        return encoder.encode(password);
    }

    /** Checks a password against a stored hash; a null hash still costs one comparison. */
    public boolean matches(String password, String hash) {
        boolean matched = encoder.matches(password == null ? "" : password, hash == null ? dummyHash : hash);
        return hash != null && matched;
    }
}
