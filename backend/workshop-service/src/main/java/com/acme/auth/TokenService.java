package com.acme.auth;

import com.acme.errors.UnauthorizedException;
import com.acme.models.User;
import com.acme.repos.RefreshTokenStore;
import com.acme.repos.SettingsStore;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and checks the two kinds of token:
 * <ul>
 *   <li>access tokens: short-lived signed JWTs whose subject is the user id, sent as "Authorization: Bearer ..."</li>
 *   <li>refresh tokens: long-lived random strings, stored only as SHA-256 hashes, each usable once</li>
 * </ul>
 * The signing secret is generated on first use and kept in the database, so every Lambda instance shares it.
 */
public class TokenService {
    public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final String ISSUER = "workshop-service";
    private static final String SECRET_SETTING = "jwt_signing_secret";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingsStore settings;
    private final RefreshTokenStore refreshTokens;
    private final Clock clock;
    private Algorithm algorithm;

    public TokenService(SettingsStore settings, RefreshTokenStore refreshTokens, Clock clock) {
        this.settings = settings;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(user.id())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(ACCESS_TOKEN_LIFETIME))
                .sign(algorithm());
    }

    /** Returns the user id the token was issued to. */
    public String verifyAccessToken(String token) {
        JWTVerifier verifier = ((JWTVerifier.BaseVerification) JWT.require(algorithm()).withIssuer(ISSUER))
                .build(clock);
        try {
            return verifier.verify(token).getSubject();
        } catch (JWTVerificationException e) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    public String issueRefreshToken(User user) {
        String token = randomToken();
        refreshTokens.insert(hash(token), user.id(), clock.instant().plus(REFRESH_TOKEN_LIFETIME));
        return token;
    }

    /**
     * Uses up a refresh token and returns the user id it belongs to. Presenting a token that was already used
     * means it may have been stolen, so every refresh token of that user is revoked.
     */
    public String redeemRefreshToken(String token) {
        RefreshTokenStore.RefreshToken stored = token == null ? null : refreshTokens.find(hash(token)).orElse(null);
        if (stored == null || stored.expiresAt().isBefore(clock.instant())) {
            throw new UnauthorizedException("Invalid or expired refresh token");
        }
        if (stored.revoked() || !refreshTokens.revoke(hash(token))) {
            refreshTokens.revokeAllFor(stored.userId());
            throw new UnauthorizedException("Invalid or expired refresh token");
        }
        return stored.userId();
    }

    public void revokeRefreshToken(String token) {
        if (token != null) {
            refreshTokens.revoke(hash(token));
        }
    }

    public void revokeAllRefreshTokens(String userId) {
        refreshTokens.revokeAllFor(userId);
    }

    private synchronized Algorithm algorithm() {
        if (algorithm == null) {
            String secret = settings.getOrCreate(SECRET_SETTING, TokenService::randomToken);
            algorithm = Algorithm.HMAC256(Base64.getUrlDecoder().decode(secret));
        }
        return algorithm;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
