package com.acme.repos;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Stores refresh token hashes in the "refresh_tokens" table; revoking sets revoked_at. */
public class PostgresRefreshTokenStore implements RefreshTokenStore {
    private final PostgresDatabase database;

    public PostgresRefreshTokenStore(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(String tokenHash, String userId, Instant expiresAt) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO refresh_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
                statement.setString(1, tokenHash);
                statement.setString(2, userId);
                statement.setObject(3, expiresAt.atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<RefreshToken> find(String tokenHash) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id, expires_at, revoked_at IS NOT NULL AS revoked FROM refresh_tokens"
                            + " WHERE token_hash = ?")) {
                statement.setString(1, tokenHash);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            ? Optional.of(new RefreshToken(row.getString("user_id"),
                                    row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                    row.getBoolean("revoked")))
                            : Optional.<RefreshToken>empty();
                }
            }
        });
    }

    @Override
    public boolean revoke(String tokenHash) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL")) {
                statement.setString(1, tokenHash);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public void revokeAllFor(String userId) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL")) {
                statement.setString(1, userId);
                statement.executeUpdate();
            }
            return null;
        });
    }
}
