package com.acme.errors;

/** The request clashes with a record that already exists. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
