package com.acme.services;

import com.acme.errors.UnauthorizedException;
import com.acme.http.Authenticator;
import com.acme.models.User;
import com.acme.repos.UserRepository;

/**
 * Checks the bearer token, then loads the user on every request, so archiving an account or changing its role
 * takes effect immediately rather than when the token expires.
 */
public class TokenAuthenticator implements Authenticator {
    private static final String BEARER = "Bearer ";

    private final TokenService tokens;
    private final UserRepository users;

    public TokenAuthenticator(TokenService tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public User authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new UnauthorizedException("Authentication required");
        }
        String userId = tokens.verifyAccessToken(authorizationHeader.substring(BEARER.length()).trim());
        return users.findById(userId)
                .filter(user -> !user.archived())
                .orElseThrow(() -> new UnauthorizedException("Account is not active"));
    }
}
