package com.acme.repos;

/** A repository could not complete an operation, for example because the database is unreachable. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
