package com.acme.http;

import com.acme.models.User;

/** Resolves the signed-in user from a request's Authorization header. */
@FunctionalInterface
public interface Authenticator {
    /** Throws UnauthorizedException when the header is missing, invalid or expired, or the account is inactive. */
    User authenticate(String authorizationHeader);
}
