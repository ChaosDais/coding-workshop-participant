package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.AuditEntry;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresStoresTest {

    @Test
    void jdbcUrlFollowsTheEnvironment() {
        assertEquals("jdbc:postgresql://172.17.0.1:5432/postgres?connectTimeout=5&socketTimeout=20",
                PostgresDatabase.jdbcUrl(Map.of("IS_LOCAL", "true", "POSTGRES_HOST", "172.17.0.1",
                        "POSTGRES_PORT", "5432", "POSTGRES_NAME", "postgres")));
        assertEquals("jdbc:postgresql://aurora.example:5432/workshop?connectTimeout=5&socketTimeout=20&sslmode=require",
                PostgresDatabase.jdbcUrl(Map.of("IS_LOCAL", "false", "POSTGRES_HOST", "aurora.example",
                        "POSTGRES_NAME", "workshop")));
    }

    /**
     * Runs against the PostgreSQL database named by POSTGRES_TEST_URL (a JDBC URL including any credentials),
     * inside a schema of its own that is dropped afterwards; skipped when the variable is not set.
     */
    @Test
    void projectsAndAuditEntriesRoundTripThroughPostgres() throws SQLException {
        String url = System.getenv("POSTGRES_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "POSTGRES_TEST_URL is not set");

        String schema = "workshop_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(url); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                PostgresDatabase database = new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null);
                ProjectStore projects = new PostgresProjectStore(database);

                Milestone design = new Milestone("m1", "Design", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                        4.5, List.of("bob", "carol"), CompletionStatus.IN_PROGRESS);
                Milestone build = new Milestone("a0", "Build", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 1), 0,
                        List.of(), CompletionStatus.TODO);
                Project project = new Project("p1", "Apollo", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                        12.5, "alice", new BigDecimal("50000.75"), CompletionStatus.TODO, List.of(design, build));
                Project earlier = new Project("p2", "Mercury", LocalDate.of(2025, 6, 1), LocalDate.of(2025, 9, 1),
                        0, "dave", BigDecimal.ZERO, CompletionStatus.COMPLETE, List.of());

                projects.insert(project);
                projects.insert(earlier);
                assertThrows(StorageException.class, () -> projects.insert(project));
                assertEquals(project, projects.findById("p1").orElseThrow());
                assertEquals(List.of(earlier, project), projects.findAll(false));

                Project updated = new Project("p1", "Gemini", project.startDate(), project.endDate(), 20, "erin",
                        new BigDecimal("1.00"), CompletionStatus.COMPLETE, List.of(build));
                assertTrue(projects.replace(updated));
                assertEquals(updated, projects.findById("p1").orElseThrow());
                assertFalse(projects.replace(new Project("missing", "n", project.startDate(), project.endDate(), 0,
                        "m", BigDecimal.ONE, null, null)));

                // The milestone left out of the update is archived, not deleted, and its id cannot be reused.
                assertEquals(1, count(admin, schema, "milestones WHERE id = 'm1' AND archived_at IS NOT NULL"));
                Project reusingId = new Project("p1", "Gemini", project.startDate(), project.endDate(), 20, "erin",
                        new BigDecimal("1.00"), CompletionStatus.COMPLETE, List.of(build, design));
                assertThrows(ConflictException.class, () -> projects.replace(reusingId));
                assertEquals(updated, projects.findById("p1").orElseThrow());

                assertTrue(projects.archive("p1"));
                assertFalse(projects.archive("p1"));
                assertTrue(projects.findById("p1").isEmpty());
                assertTrue(projects.exists("p1"));
                assertEquals(List.of(earlier), projects.findAll(false));
                assertEquals(List.of(updated), projects.findAll(true));
                assertFalse(projects.replace(updated));
                assertTrue(projects.restore("p1"));
                assertFalse(projects.restore("p1"));
                assertEquals(updated, projects.findById("p1").orElseThrow());

                // Nothing was deleted along the way.
                assertEquals(2, count(admin, schema, "projects"));
                assertEquals(2, count(admin, schema, "milestones"));

                AuditStore audit = new PostgresAuditStore(database);
                AuditEntry entry = new AuditEntry("project_created", "alice", Map.of("projectId", "p1"));
                audit.insert(entry);
                // A second database object sees the same rows, so nothing depends on in-memory state.
                AuditStore reopened = new PostgresAuditStore(new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null));
                assertEquals(List.of(entry), reopened.findRecent(10));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    /** Tables created before archiving existed get the new columns, and milestones no longer cascade on delete. */
    @Test
    void tablesFromTheEarlierVersionAreUpgraded() throws SQLException {
        String url = System.getenv("POSTGRES_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "POSTGRES_TEST_URL is not set");

        String schema = "workshop_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(url); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("CREATE TABLE " + schema + ".projects (id TEXT PRIMARY KEY, name TEXT NOT NULL,"
                        + " start_date DATE NOT NULL, end_date DATE NOT NULL, progress_time DOUBLE PRECISION NOT NULL,"
                        + " manager TEXT NOT NULL, budget NUMERIC NOT NULL, status TEXT NOT NULL)");
                statement.execute("CREATE TABLE " + schema + ".milestones (project_id TEXT NOT NULL"
                        + " REFERENCES " + schema + ".projects (id) ON DELETE CASCADE, id TEXT NOT NULL,"
                        + " sort_order INTEGER NOT NULL, name TEXT NOT NULL, start_date DATE NOT NULL,"
                        + " end_date DATE NOT NULL, progress_time DOUBLE PRECISION NOT NULL,"
                        + " assigned_employees TEXT[] NOT NULL, status TEXT NOT NULL, PRIMARY KEY (project_id, id))");
                statement.execute("INSERT INTO " + schema + ".projects VALUES"
                        + " ('old', 'Old', '2026-01-01', '2026-02-01', 0, 'm', 1, 'TODO')");
                statement.execute("INSERT INTO " + schema + ".milestones VALUES"
                        + " ('old', 'm1', 0, 'Kickoff', '2026-01-01', '2026-01-02', 0, '{}', 'TODO')");

                ProjectStore projects = new PostgresProjectStore(new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null));

                assertEquals("Kickoff", projects.findById("old").orElseThrow().milestones().get(0).name());
                assertTrue(projects.archive("old"));
                // Deleting a project that has milestones is now refused instead of cascading.
                assertThrows(SQLException.class,
                        () -> statement.execute("DELETE FROM " + schema + ".projects WHERE id = 'old'"));
                assertEquals(1, count(admin, schema, "milestones"));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static int count(Connection connection, String schema, String tableAndCondition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT count(*) FROM " + schema + "." + tableAndCondition)) {
            row.next();
            return row.getInt(1);
        }
    }
}
