package com.acme.errors;

/** The caller is signed in but their role does not allow this action. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
