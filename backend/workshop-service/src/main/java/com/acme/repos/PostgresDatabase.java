package com.acme.repos;

import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Holds one PostgreSQL connection per Lambda instance, opened on first use and reopened when it has gone stale.
 * The tables are created or upgraded on first connect, so a fresh database needs no setup.
 */
public class PostgresDatabase {
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                start_date DATE NOT NULL,
                end_date DATE NOT NULL,
                progress_time DOUBLE PRECISION NOT NULL,
                manager TEXT NOT NULL,
                budget NUMERIC NOT NULL,
                status TEXT NOT NULL,
                archived_at TIMESTAMPTZ
            )""",
            """
            CREATE TABLE IF NOT EXISTS milestones (
                project_id TEXT NOT NULL REFERENCES projects (id),
                id TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                name TEXT NOT NULL,
                start_date DATE NOT NULL,
                end_date DATE NOT NULL,
                progress_time DOUBLE PRECISION NOT NULL,
                assigned_employees TEXT[] NOT NULL,
                status TEXT NOT NULL,
                archived_at TIMESTAMPTZ,
                PRIMARY KEY (project_id, id)
            )""",
            """
            CREATE TABLE IF NOT EXISTS audit_entries (
                id TEXT PRIMARY KEY,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                details JSONB NOT NULL,
                occurred_at TIMESTAMPTZ NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                manager_id TEXT REFERENCES users (id),
                job_type TEXT,
                salary NUMERIC,
                password_hash TEXT NOT NULL,
                password_change_required BOOLEAN NOT NULL DEFAULT false,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                archived_at TIMESTAMPTZ
            )""",
            """
            CREATE TABLE IF NOT EXISTS refresh_tokens (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users (id),
                expires_at TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                revoked_at TIMESTAMPTZ
            )""",
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                name TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )""",
            """
            CREATE TABLE IF NOT EXISTS time_entries (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                milestone_id TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES users (id),
                source TEXT NOT NULL,
                note TEXT,
                started_at TIMESTAMPTZ,
                ended_at TIMESTAMPTZ,
                hours NUMERIC,
                hourly_rate NUMERIC,
                cost NUMERIC,
                logged_at TIMESTAMPTZ NOT NULL,
                corrected_at TIMESTAMPTZ,
                corrected_by TEXT,
                correction_reason TEXT,
                FOREIGN KEY (project_id, milestone_id) REFERENCES milestones (project_id, id)
            )""",
            // An employee works toward one milestone at a time, so at most one timer runs per user.
            """
            CREATE UNIQUE INDEX IF NOT EXISTS time_entries_one_running_timer
                ON time_entries (user_id) WHERE source = 'TIMER' AND ended_at IS NULL""",
            // Upgrades tables created by earlier versions; rows are archived, never deleted.
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS job_type TEXT",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS salary NUMERIC",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT false",
            "ALTER TABLE milestones ADD COLUMN IF NOT EXISTS description TEXT",
            "ALTER TABLE projects ADD COLUMN IF NOT EXISTS description TEXT",
            "ALTER TABLE projects ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ",
            "ALTER TABLE projects ADD COLUMN IF NOT EXISTS depends_on TEXT[] NOT NULL DEFAULT '{}'",
            "ALTER TABLE milestones ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ",
            "ALTER TABLE milestones ADD COLUMN IF NOT EXISTS depends_on TEXT[] NOT NULL DEFAULT '{}'",
            "ALTER TABLE time_entries ADD COLUMN IF NOT EXISTS corrected_at TIMESTAMPTZ",
            "ALTER TABLE time_entries ADD COLUMN IF NOT EXISTS corrected_by TEXT",
            "ALTER TABLE time_entries ADD COLUMN IF NOT EXISTS correction_reason TEXT",
            "ALTER TABLE projects ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ",
            "ALTER TABLE milestones ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ",
            // Older tables deleted milestones along with their project; now deleting a referenced project fails.
            """
            DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'milestones'::regclass
                           AND conname = 'milestones_project_id_fkey' AND confdeltype = 'c') THEN
                    ALTER TABLE milestones DROP CONSTRAINT milestones_project_id_fkey;
                    ALTER TABLE milestones ADD CONSTRAINT milestones_project_id_fkey
                        FOREIGN KEY (project_id) REFERENCES projects (id);
                END IF;
            END $$""",
    };

    /**
     * How long to keep trying to connect. Aurora Serverless pauses after a few idle minutes and takes about 15
     * seconds to resume; until then connection attempts time out. 22 seconds of retrying plus a Lambda cold start
     * stays inside CloudFront's 30-second origin timeout.
     */
    static final Duration RESUME_WINDOW = Duration.ofSeconds(22);
    private static final long RETRY_PAUSE_MILLIS = 1000;
    /** SQLSTATE class 08: the connection could not be made or was lost; anything else will not fix itself. */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    /** One connection attempt. */
    @FunctionalInterface
    interface ConnectionAttempt {
        Connection connect() throws SQLException;
    }

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Retries a failed connection while the error says the server could not be reached and the window has not
     * passed, so a paused database gets time to resume. Other errors, such as a wrong password, fail at once.
     */
    static Connection retryWhileResuming(ConnectionAttempt attempt, Duration window, Pause pause)
            throws SQLException {
        long deadline = System.nanoTime() + window.toNanos();
        while (true) {
            try {
                return attempt.connect();
            } catch (SQLException e) {
                boolean unreachable = e.getSQLState() != null && e.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS);
                if (!unreachable || System.nanoTime() + RETRY_PAUSE_MILLIS * 1_000_000 > deadline) {
                    throw e;
                }
                try {
                    pause.sleep(RETRY_PAUSE_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Work done on the connection inside one transaction. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final String url;
    private final Properties properties = new Properties();
    private Connection connection;

    public PostgresDatabase(String url, String user, String password) {
        this.url = url;
        if (user != null && !user.isBlank()) {
            properties.setProperty("user", user);
            properties.setProperty("password", password == null ? "" : password);
        }
    }

    /** Uses the POSTGRES_* variables the deploy scripts give each Lambda. */
    public static PostgresDatabase fromEnvironment(Map<String, String> env) {
        return new PostgresDatabase(jdbcUrl(env), env.get("POSTGRES_USER"), env.get("POSTGRES_PASS"));
    }

    static String jdbcUrl(Map<String, String> env) {
        String host = valueOrDefault(env, "POSTGRES_HOST", "localhost");
        String port = valueOrDefault(env, "POSTGRES_PORT", "5432");
        String name = valueOrDefault(env, "POSTGRES_NAME", "postgres");
        // Timeouts stay well inside CloudFront's 30 second origin timeout so failures are reported as JSON.
        String options = "connectTimeout=5&socketTimeout=20";
        // Aurora requires SSL; the local PostgreSQL runs without it.
        if (!"true".equals(env.get("IS_LOCAL"))) {
            options += "&sslmode=require";
        }
        return String.format("jdbc:postgresql://%s:%s/%s?%s", host, port, name, options);
    }

    /** Runs the work in a transaction: committed when it returns, rolled back when it throws. */
    public synchronized <T> T run(SqlWork<T> work) {
        try {
            Connection current = connection();
            try {
                current.setAutoCommit(false);
                T result = work.apply(current);
                current.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                current.rollback();
                throw e;
            } finally {
                current.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("PostgreSQL operation failed: " + e.getMessage(), e);
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            // The shaded jar drops the service files DriverManager relies on, so the driver is called directly.
            connection = retryWhileResuming(() -> new Driver().connect(url, properties), RESUME_WINDOW,
                    Thread::sleep);
            try (Statement statement = connection.createStatement()) {
                for (String table : SCHEMA) {
                    statement.execute(table);
                }
            }
        }
        return connection;
    }

    private static String valueOrDefault(Map<String, String> env, String key, String defaultValue) {
        String value = env.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
