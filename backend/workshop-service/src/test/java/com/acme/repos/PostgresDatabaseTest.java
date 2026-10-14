package com.acme.repos;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Retrying while a paused Aurora database resumes. */
class PostgresDatabaseTest {
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final SQLException UNREACHABLE = new SQLException("The connection attempt failed.", "08001");
    private static final SQLException WRONG_PASSWORD = new SQLException("password authentication failed", "28P01");

    @Test
    void keepsTryingWhileTheServerCannotBeReached() throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        Connection connected = PostgresDatabase.retryWhileResuming(() -> {
            if (attempts.incrementAndGet() < 4) {
                throw UNREACHABLE;
            }
            return CONNECTION;
        }, Duration.ofSeconds(22), millis -> { });

        assertSame(CONNECTION, connected);
        assertEquals(4, attempts.get());
    }

    @Test
    void errorsThatWaitingCannotFixFailAtOnce() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException thrown = assertThrows(SQLException.class, () -> PostgresDatabase.retryWhileResuming(() -> {
            attempts.incrementAndGet();
            throw WRONG_PASSWORD;
        }, Duration.ofSeconds(22), millis -> { }));

        assertSame(WRONG_PASSWORD, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    void givesUpWhenTheWindowHasPassed() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(SQLException.class, () -> PostgresDatabase.retryWhileResuming(() -> {
            attempts.incrementAndGet();
            throw UNREACHABLE;
        }, Duration.ofMillis(500), millis -> { }));
        assertEquals(1, attempts.get());
    }
}
