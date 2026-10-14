package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores projects in the "projects" table and their milestones, in list order, in "milestones".
 * Rows are never deleted; archived ones have archived_at set and are left out of the active views.
 */
public class PostgresProjectRepository implements ProjectRepository {
    private static final String PROJECT_COLUMNS =
            "id, name, start_date, end_date, progress_time, manager, budget, status, description, completed_at";
    private static final String MILESTONE_COLUMNS =
            "project_id, id, name, start_date, end_date, progress_time, assigned_employees, status, description,"
                    + " completed_at, depends_on";

    private final PostgresDatabase database;

    public PostgresProjectRepository(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(Project project) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO projects (" + PROJECT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, project.id());
                statement.setString(2, project.name());
                statement.setObject(3, project.startDate());
                statement.setObject(4, project.endDate());
                statement.setDouble(5, project.progressTime());
                statement.setString(6, project.manager());
                statement.setBigDecimal(7, project.budget());
                statement.setString(8, project.status().name());
                statement.setString(9, project.description());
                statement.setObject(10, utc(project.completedAt()));
                statement.executeUpdate();
            }
            saveMilestones(connection, project);
            return null;
        });
    }

    @Override
    public boolean exists(String id) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM projects WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next();
                }
            }
        });
    }

    @Override
    public Optional<Project> findById(String id) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE id = ? AND archived_at IS NULL")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Optional.<Project>empty();
                    }
                    return Optional.of(toProject(row, findMilestones(connection, id).getOrDefault(id, List.of())));
                }
            }
        });
    }

    @Override
    public List<Project> findAll(boolean archived) {
        return database.run(connection -> {
            Map<String, List<Milestone>> milestones = findMilestones(connection, null);
            List<Project> projects = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE archived_at IS "
                            + (archived ? "NOT NULL" : "NULL") + " ORDER BY start_date, name");
                 ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    projects.add(toProject(row, milestones.getOrDefault(row.getString("id"), List.of())));
                }
            }
            return projects;
        });
    }

    @Override
    public boolean replace(Project project) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE projects SET name = ?, start_date = ?, end_date = ?, progress_time = ?, manager = ?,"
                            + " budget = ?, status = ?, description = ?, completed_at = ?"
                            + " WHERE id = ? AND archived_at IS NULL")) {
                statement.setString(1, project.name());
                statement.setObject(2, project.startDate());
                statement.setObject(3, project.endDate());
                statement.setDouble(4, project.progressTime());
                statement.setString(5, project.manager());
                statement.setBigDecimal(6, project.budget());
                statement.setString(7, project.status().name());
                statement.setString(8, project.description());
                statement.setObject(9, utc(project.completedAt()));
                statement.setString(10, project.id());
                if (statement.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE milestones SET archived_at = now()"
                            + " WHERE project_id = ? AND archived_at IS NULL AND NOT (id = ANY (?))")) {
                statement.setString(1, project.id());
                statement.setArray(2, connection.createArrayOf("text",
                        project.milestones().stream().map(Milestone::id).toArray()));
                statement.executeUpdate();
            }
            saveMilestones(connection, project);
            return true;
        });
    }

    @Override
    public boolean setMilestoneStatus(String projectId, String milestoneId, CompletionStatus status,
                                      Instant completedAt) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE milestones SET status = ?, completed_at = ?"
                            + " WHERE project_id = ? AND id = ? AND archived_at IS NULL"
                            + " AND EXISTS (SELECT 1 FROM projects WHERE id = ? AND archived_at IS NULL)")) {
                statement.setString(1, status.name());
                statement.setObject(2, utc(completedAt));
                statement.setString(3, projectId);
                statement.setString(4, milestoneId);
                statement.setString(5, projectId);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean archive(String id) {
        return setArchived(id, true);
    }

    @Override
    public boolean restore(String id) {
        return setArchived(id, false);
    }

    private boolean setArchived(String id, boolean archived) {
        return database.run(connection -> {
            // A project's milestones keep their own state, so a restored project gets back the ones it had.
            try (PreparedStatement statement = connection.prepareStatement(archived
                    ? "UPDATE projects SET archived_at = now() WHERE id = ? AND archived_at IS NULL"
                    : "UPDATE projects SET archived_at = NULL WHERE id = ? AND archived_at IS NOT NULL")) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    /** Inserts or updates the project's active milestones, in list order. */
    private static void saveMilestones(Connection connection, Project project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO milestones (" + MILESTONE_COLUMNS + ", sort_order)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (project_id, id) DO UPDATE SET name = EXCLUDED.name,"
                        + " description = EXCLUDED.description, completed_at = EXCLUDED.completed_at,"
                        + " depends_on = EXCLUDED.depends_on,"
                        + " start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date,"
                        + " progress_time = EXCLUDED.progress_time, assigned_employees = EXCLUDED.assigned_employees,"
                        + " status = EXCLUDED.status, sort_order = EXCLUDED.sort_order"
                        // Archived milestones are never overwritten, so their ids cannot be reused.
                        + " WHERE milestones.archived_at IS NULL")) {
            int sortOrder = 0;
            for (Milestone milestone : project.milestones()) {
                statement.setString(1, project.id());
                statement.setString(2, milestone.id());
                statement.setString(3, milestone.name());
                statement.setObject(4, milestone.startDate());
                statement.setObject(5, milestone.endDate());
                statement.setDouble(6, milestone.progressTime());
                statement.setArray(7, connection.createArrayOf("text", milestone.assignedEmployees().toArray()));
                statement.setString(8, milestone.status().name());
                statement.setString(9, milestone.description());
                statement.setObject(10, utc(milestone.completedAt()));
                statement.setArray(11, connection.createArrayOf("text", milestone.dependsOn().toArray()));
                statement.setInt(12, sortOrder++);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] == 0) {
                    // Rolls back the whole transaction, so nothing of this change is stored.
                    throw new ConflictException("Milestone id " + project.milestones().get(index).id()
                            + " belongs to an archived milestone");
                }
            }
        }
    }

    /** Active milestones by project id, for one project or, when projectId is null, for all of them. */
    private static Map<String, List<Milestone>> findMilestones(Connection connection, String projectId)
            throws SQLException {
        Map<String, List<Milestone>> byProject = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + MILESTONE_COLUMNS + " FROM milestones WHERE archived_at IS NULL"
                        + (projectId == null ? "" : " AND project_id = ?") + " ORDER BY project_id, sort_order")) {
            if (projectId != null) {
                statement.setString(1, projectId);
            }
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    byProject.computeIfAbsent(row.getString("project_id"), id -> new ArrayList<>()).add(new Milestone(
                            row.getString("id"),
                            row.getString("name"),
                            row.getString("description"),
                            row.getObject("start_date", LocalDate.class),
                            row.getObject("end_date", LocalDate.class),
                            row.getDouble("progress_time"),
                            List.of((String[]) row.getArray("assigned_employees").getArray()),
                            CompletionStatus.valueOf(row.getString("status")),
                            instant(row, "completed_at"),
                            List.of((String[]) row.getArray("depends_on").getArray())));
                }
            }
        }
        return byProject;
    }

    private static Project toProject(ResultSet row, List<Milestone> milestones) throws SQLException {
        return new Project(
                row.getString("id"),
                row.getString("name"),
                row.getString("description"),
                row.getObject("start_date", LocalDate.class),
                row.getObject("end_date", LocalDate.class),
                row.getDouble("progress_time"),
                row.getString("manager"),
                row.getBigDecimal("budget"),
                CompletionStatus.valueOf(row.getString("status")),
                milestones,
                instant(row, "completed_at"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
