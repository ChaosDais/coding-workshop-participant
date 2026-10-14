package com.acme.repos;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for the PostgreSQL repository. */
public class InMemoryRefreshTokenRepository implements RefreshTokenRepository {
    private final Map<String, RefreshToken> tokens = new ConcurrentHashMap<>();

    @Override
    public void insert(String tokenHash, String userId, Instant expiresAt) {
        tokens.put(tokenHash, new RefreshToken(userId, expiresAt, false));
    }

    @Override
    public Optional<RefreshToken> find(String tokenHash) {
        return Optional.ofNullable(tokens.get(tokenHash));
    }

    @Override
    public synchronized boolean revoke(String tokenHash) {
        RefreshToken token = tokens.get(tokenHash);
        if (token == null || token.revoked()) {
            return false;
        }
        tokens.put(tokenHash, new RefreshToken(token.userId(), token.expiresAt(), true));
        return true;
    }

    @Override
    public synchronized void revokeAllFor(String userId) {
        tokens.replaceAll((hash, token) -> token.userId().equals(userId)
                ? new RefreshToken(token.userId(), token.expiresAt(), true) : token);
    }
}
