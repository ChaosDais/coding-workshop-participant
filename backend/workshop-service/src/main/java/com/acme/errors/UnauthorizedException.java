package com.acme.errors;

/** The caller is not signed in, or their credentials or token are invalid or expired. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
