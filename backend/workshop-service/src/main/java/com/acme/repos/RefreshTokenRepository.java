package com.acme.repos;

import java.time.Instant;
import java.util.Optional;

/** Refresh tokens, stored only as hashes; revoked tokens are kept, never deleted. */
public interface RefreshTokenRepository {
    record RefreshToken(String userId, Instant expiresAt, boolean revoked) {
    }

    void insert(String tokenHash, String userId, Instant expiresAt);

    Optional<RefreshToken> find(String tokenHash);

    /** Returns true only for the call that revoked an active token, so a token can be redeemed once. */
    boolean revoke(String tokenHash);

    void revokeAllFor(String userId);
}
