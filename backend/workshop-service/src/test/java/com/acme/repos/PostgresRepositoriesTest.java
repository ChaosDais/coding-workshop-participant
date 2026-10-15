package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.models.Role;
import com.acme.models.TimeEntry;
import com.acme.models.User;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRepositoriesTest {

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
                ProjectRepository projects = new PostgresProjectRepository(database);

                Milestone design = new Milestone("m1", "Design", "Wireframes and visual design",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), 4.5, List.of("bob", "carol"),
                        CompletionStatus.IN_PROGRESS, null, null, 40.0, null);
                Milestone build = new Milestone("a0", "Build", null, LocalDate.of(2026, 2, 2),
                        LocalDate.of(2026, 3, 1), 0, List.of(), CompletionStatus.TODO, null, List.of("m1"), null, null);
                Project project = new Project("p1", "Apollo", "Crewed lunar landing", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31), 12.5, "alice", new BigDecimal("50000.75"), CompletionStatus.TODO,
                        List.of(design, build), null, null, 160.0, null);
                Project earlier = new Project("p2", "Mercury", null, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 9, 1),
                        0, "dave", BigDecimal.ZERO, CompletionStatus.COMPLETE, List.of(), null, List.of("p1"), null,
                        null);

                projects.insert(project);
                projects.insert(earlier);
                assertThrows(StorageException.class, () -> projects.insert(project));
                assertEquals(project, projects.findById("p1").orElseThrow());
                assertEquals(List.of(earlier, project), projects.findAll(false));

                Project updated = new Project("p1", "Gemini", null, project.startDate(), project.endDate(), 20, "erin",
                        new BigDecimal("1.00"), CompletionStatus.COMPLETE, List.of(build), null, null, null, null);
                assertTrue(projects.replace(updated));
                assertEquals(updated, projects.findById("p1").orElseThrow());
                assertFalse(projects.replace(new Project("missing", "n", null, project.startDate(),
                        project.endDate(), 0, "m", BigDecimal.ONE, null, null, null, null, null, null)));

                // The milestone left out of the update is archived, not deleted, and its id cannot be reused.
                assertEquals(1, count(admin, schema, "milestones WHERE id = 'm1' AND archived_at IS NOT NULL"));
                assertEquals(List.of("m1"), projects.findArchivedMilestones("p1").stream().map(Milestone::id)
                        .toList());
                Project reusingId = new Project("p1", "Gemini", null, project.startDate(), project.endDate(), 20,
                        "erin", new BigDecimal("1.00"), CompletionStatus.COMPLETE, List.of(build, design), null, null,
                        null, null);
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

                AuditRepository audit = new PostgresAuditRepository(database);
                AuditEntry entry = new AuditEntry("project_created", "alice", Map.of("projectId", "p1"),
                        Instant.parse("2026-10-14T12:00:00Z"));
                AuditEntry older = new AuditEntry("milestone_added", "bob", Map.of("projectId", "p1",
                        "milestoneId", "m1", "note", "100%_done"), Instant.parse("2026-10-12T08:00:00Z"));
                AuditEntry other = new AuditEntry("user_created", "alice", Map.of("userId", "u1"),
                        Instant.parse("2026-10-13T08:00:00Z"));
                audit.insert(entry);
                audit.insert(older);
                audit.insert(other);
                // A second database object sees the same rows, so nothing depends on in-memory state.
                AuditRepository reopened = new PostgresAuditRepository(new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null));
                assertEquals(List.of(entry, other, older), reopened.find(AuditQuery.recent(10)));
                assertEquals(List.of(entry, older), reopened.find(
                        new AuditQuery("p1", null, null, null, null, null, 10)));
                assertEquals(List.of(older), reopened.find(new AuditQuery(null, "m1", null, null, null, null, 10)));
                assertEquals(List.of(entry, other), reopened.find(
                        new AuditQuery(null, null, List.of("project_created", "user_created"), null, null, null, 10)));
                assertEquals(List.of(other), reopened.find(new AuditQuery(null, null, null,
                        LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 13), null, 10)));
                assertEquals(List.of(other, older), reopened.find(
                        new AuditQuery(null, null, null, null, LocalDate.of(2026, 10, 13), null, 10)));
                // Text matching ignores case, and % and _ are matched literally rather than as wildcards.
                assertEquals(List.of(entry, other), reopened.find(new AuditQuery(null, null, null, null, null, "ALICE",
                        10)));
                assertEquals(List.of(older), reopened.find(new AuditQuery(null, null, null, null, null, "100%_", 10)));
                assertEquals(List.of(), reopened.find(new AuditQuery(null, null, null, null, null, "1%0", 10)));
                assertEquals(List.of(entry), reopened.find(AuditQuery.recent(1)));
                // Pages: skip the first entries, and count all matches regardless of the page.
                AuditQuery secondPage = new AuditQuery(null, null, null, null, null, null, 2, 2);
                assertEquals(List.of(older), reopened.find(secondPage));
                assertEquals(3, reopened.count(secondPage));
                assertEquals(1, reopened.count(new AuditQuery(null, "m1", null, null, null, null, 1)));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    /** Accounts, refresh tokens and settings round-trip through PostgreSQL; nothing is ever deleted. */
    @Test
    void usersTokensAndSettingsRoundTripThroughPostgres() throws SQLException {
        String url = System.getenv("POSTGRES_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "POSTGRES_TEST_URL is not set");

        String schema = "workshop_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(url); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                PostgresDatabase database = new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null);
                UserRepository users = new PostgresUserRepository(database);

                User boss = new User("u1", "Boss@Example.com", "Boss", Role.ADMIN, null, null, null, false, false);
                User manager = new User("u2", "manager@example.com", "Manager", Role.MANAGER, null, "Team lead",
                        new BigDecimal("95000.50"), false, false);
                User employee = new User("u3", "employee@example.com", "Employee", Role.EMPLOYEE, "u2", "Developer",
                        new BigDecimal("70000"), false, false);
                assertTrue(users.insertIfEmpty(boss, "hash-1"));
                assertFalse(users.insertIfEmpty(manager, "hash-2"));
                users.insert(manager, "hash-2");
                users.insert(employee, "hash-3");
                assertThrows(ConflictException.class, () -> users.insert(
                        new User("u4", "MANAGER@example.com", "Dup", Role.EMPLOYEE, null, null, null, false, false),
                        "h"));

                assertEquals(boss, users.findById("u1").orElseThrow());
                assertEquals(manager, users.findById("u2").orElseThrow());
                assertEquals("hash-1", users.findByEmail("boss@example.com").orElseThrow().passwordHash());
                assertEquals(List.of(boss, employee, manager), users.findAll(false));
                assertEquals(1, users.countActive(Role.ADMIN));
                assertEquals(1, users.countActiveEmployeesOf("u2"));

                User promoted = new User("u3", "employee@example.com", "Employee", Role.EMPLOYEE, null,
                        "Senior developer", new BigDecimal("80000.00"), false, false);
                users.update(promoted);
                assertEquals(0, users.countActiveEmployeesOf("u2"));
                assertEquals(promoted, users.findById("u3").orElseThrow());
                assertThrows(ConflictException.class, () -> users.update(
                        new User("u3", "boss@example.com", "Employee", Role.EMPLOYEE, null, null, null, false, false)));
                users.setPasswordHash("u3", "hash-new");
                assertEquals("hash-new", users.findByEmail("employee@example.com").orElseThrow().passwordHash());

                assertTrue(users.archive("u3"));
                assertFalse(users.archive("u3"));
                assertTrue(users.findById("u3").orElseThrow().archived());
                assertEquals(List.of("u3"), users.findAll(true).stream().map(User::id).toList());
                assertTrue(users.restore("u3"));

                RefreshTokenRepository tokens = new PostgresRefreshTokenRepository(database);
                Instant expiry = Instant.parse("2026-10-21T12:00:00Z");
                tokens.insert("token-hash", "u1", expiry);
                tokens.insert("other-hash", "u1", expiry);
                assertEquals(new RefreshTokenRepository.RefreshToken("u1", expiry, false),
                        tokens.find("token-hash").orElseThrow());
                assertTrue(tokens.revoke("token-hash"));
                assertFalse(tokens.revoke("token-hash"));
                tokens.revokeAllFor("u1");
                assertTrue(tokens.find("other-hash").orElseThrow().revoked());

                SettingsRepository settings = new PostgresSettingsRepository(database);
                assertEquals("first", settings.getOrCreate("secret", () -> "first"));
                assertEquals("first", settings.getOrCreate("secret", () -> "second"));
                assertEquals(Optional.empty(), settings.get("thresholds"));
                settings.put("thresholds", "a");
                settings.put("thresholds", "b");
                assertEquals(Optional.of("b"), settings.get("thresholds"));

                assertEquals(3, count(admin, schema, "users"));
                assertEquals(2, count(admin, schema, "refresh_tokens"));
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    /** Time entries add to milestone progress and project cost, and the database allows one running timer each. */
    @Test
    void timeEntriesRoundTripThroughPostgres() throws SQLException {
        String url = System.getenv("POSTGRES_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "POSTGRES_TEST_URL is not set");

        String schema = "workshop_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(url); Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                PostgresDatabase database = new PostgresDatabase(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, null, null);
                new PostgresUserRepository(database).insert(
                        new User("e1", "e1@example.com", "E1", Role.EMPLOYEE, null, null, null, false, false), "hash");
                ProjectRepository projects = new PostgresProjectRepository(database);
                Milestone milestone = new Milestone("m1", "Design", null, LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 2, 1), 1.0, List.of("e1"), CompletionStatus.IN_PROGRESS, null, null, null,
                        null);
                projects.insert(new Project("p1", "Apollo", null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                        0, "m", BigDecimal.TEN, null, List.of(milestone), null, null, null, null));
                TimeEntryRepository entries = new PostgresTimeEntryRepository(database);
                Instant start = Instant.parse("2026-10-14T09:00:00Z");

                TimeEntry manual = new TimeEntry("t1", "p1", "m1", "e1", TimeEntry.Source.MANUAL, "Wireframes", null,
                        null, new BigDecimal("2.50"), new BigDecimal("50.0000"), new BigDecimal("125.00"), start,
                        null, null, null);
                entries.insertLogged(manual);
                assertEquals(3.5, projects.findById("p1").orElseThrow().milestones().get(0).progressTime());

                TimeEntry timer = new TimeEntry("t2", "p1", "m1", "e1", TimeEntry.Source.TIMER, null, start, null,
                        null, null, null, start, null, null, null);
                entries.insertRunning(timer);
                assertThrows(ConflictException.class, () -> entries.insertRunning(new TimeEntry("t3", "p1", "m1",
                        "e1", TimeEntry.Source.TIMER, null, start, null, null, null, null, start, null, null, null)));
                assertEquals(timer, entries.findRunning("e1").orElseThrow());

                Instant end = start.plusSeconds(5400);
                TimeEntry stopped = new TimeEntry("t2", "p1", "m1", "e1", TimeEntry.Source.TIMER, null, start, end,
                        new BigDecimal("1.50"), new BigDecimal("50.0000"), new BigDecimal("75.00"), end,
                        null, null, null);
                assertTrue(entries.stop(stopped));
                assertFalse(entries.stop(stopped));
                assertTrue(entries.findRunning("e1").isEmpty());
                assertEquals(5.0, projects.findById("p1").orElseThrow().milestones().get(0).progressTime());
                assertEquals(List.of(manual, stopped), entries.findForMilestone("p1", "m1"));
                assertEquals(List.of(manual, stopped), entries.findLoggedForUser("e1"));
                assertTrue(entries.findLoggedForUser("someone-else").isEmpty());
                assertEquals(0, new BigDecimal("200.00").compareTo(entries.totalCostByProject().get("p1")));

                // A correction moves progress by the difference, and only applies to the hours it was based on.
                Instant fixedAt = end.plusSeconds(60);
                TimeEntry corrected = new TimeEntry("t1", "p1", "m1", "e1", TimeEntry.Source.MANUAL, "Wireframes",
                        null, null, new BigDecimal("0.50"), new BigDecimal("50.0000"), new BigDecimal("25.00"), start,
                        fixedAt, "admin@example.com", "Typo");
                assertTrue(entries.correct(corrected, new BigDecimal("2.50")));
                assertFalse(entries.correct(corrected, new BigDecimal("2.50")));
                assertEquals(corrected, entries.findById("t1").orElseThrow());
                assertEquals(3.0, projects.findById("p1").orElseThrow().milestones().get(0).progressTime());
                assertEquals(0, new BigDecimal("100.00").compareTo(entries.totalCostByProject().get("p1")));

                // Assignee changes touch only the assignees, and apply to every milestone or to none.
                assertTrue(projects.setAssignees("p1", Map.of("m1", List.of("e1", "e2"))));
                assertEquals(List.of("e1", "e2"), projects.findById("p1").orElseThrow().milestones().get(0)
                        .assignedEmployees());
                assertEquals(3.0, projects.findById("p1").orElseThrow().milestones().get(0).progressTime());
                // m1 is changed first, then the missing milestone makes the whole change roll back.
                Map<String, List<String>> partlyMissing = new java.util.LinkedHashMap<>();
                partlyMissing.put("m1", List.of());
                partlyMissing.put("missing", List.of("e1"));
                assertFalse(projects.setAssignees("p1", partlyMissing));
                assertEquals(List.of("e1", "e2"), projects.findById("p1").orElseThrow().milestones().get(0)
                        .assignedEmployees());

                // Changing the status leaves the logged progress alone, and stores the completion time.
                assertTrue(projects.setMilestoneStatus("p1", "m1", CompletionStatus.AWAITING_REVIEW, null, fixedAt));
                Milestone reviewed = projects.findById("p1").orElseThrow().milestones().get(0);
                assertEquals(CompletionStatus.AWAITING_REVIEW, reviewed.status());
                assertEquals(3.0, reviewed.progressTime());
                assertTrue(projects.setMilestoneStatus("p1", "m1", CompletionStatus.COMPLETE, fixedAt, fixedAt));
                assertEquals(fixedAt, projects.findById("p1").orElseThrow().milestones().get(0).completedAt());
                assertEquals(fixedAt, projects.findById("p1").orElseThrow().milestones().get(0).statusChangedAt());
                assertFalse(projects.setMilestoneStatus("p1", "missing", CompletionStatus.COMPLETE, fixedAt, fixedAt));
                assertTrue(projects.archive("p1"));
                assertFalse(projects.setMilestoneStatus("p1", "m1", CompletionStatus.COMPLETE, fixedAt, fixedAt));
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

                ProjectRepository projects = new PostgresProjectRepository(new PostgresDatabase(
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
