package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores projects in the "projects" table and their milestones, in list order, in "milestones". */
public class PostgresProjectStore implements ProjectStore {
    private static final String PROJECT_COLUMNS = "id, name, start_date, end_date, progress_time, manager, budget, status";
    private static final String MILESTONE_COLUMNS =
            "project_id, id, name, start_date, end_date, progress_time, assigned_employees, status";

    private final PostgresDatabase database;

    public PostgresProjectStore(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(Project project) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO projects (" + PROJECT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, project.id());
                statement.setString(2, project.name());
                statement.setObject(3, project.startDate());
                statement.setObject(4, project.endDate());
                statement.setDouble(5, project.progressTime());
                statement.setString(6, project.manager());
                statement.setBigDecimal(7, project.budget());
                statement.setString(8, project.status().name());
                statement.executeUpdate();
            }
            insertMilestones(connection, project);
            return null;
        });
    }

    @Override
    public Optional<Project> findById(String id) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE id = ?")) {
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
    public List<Project> findAll() {
        return database.run(connection -> {
            Map<String, List<Milestone>> milestones = findMilestones(connection, null);
            List<Project> projects = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + PROJECT_COLUMNS + " FROM projects ORDER BY start_date, name");
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
                            + " budget = ?, status = ? WHERE id = ?")) {
                statement.setString(1, project.name());
                statement.setObject(2, project.startDate());
                statement.setObject(3, project.endDate());
                statement.setDouble(4, project.progressTime());
                statement.setString(5, project.manager());
                statement.setBigDecimal(6, project.budget());
                statement.setString(7, project.status().name());
                statement.setString(8, project.id());
                if (statement.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM milestones WHERE project_id = ?")) {
                statement.setString(1, project.id());
                statement.executeUpdate();
            }
            insertMilestones(connection, project);
            return true;
        });
    }

    @Override
    public boolean deleteById(String id) {
        return database.run(connection -> {
            // Milestones go with the project through ON DELETE CASCADE.
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM projects WHERE id = ?")) {
                statement.setString(1, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static void insertMilestones(Connection connection, Project project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO milestones (" + MILESTONE_COLUMNS + ", sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                statement.setInt(9, sortOrder++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Milestones by project id, for one project or, when projectId is null, for all of them. */
    private static Map<String, List<Milestone>> findMilestones(Connection connection, String projectId)
            throws SQLException {
        Map<String, List<Milestone>> byProject = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + MILESTONE_COLUMNS + " FROM milestones"
                        + (projectId == null ? "" : " WHERE project_id = ?") + " ORDER BY project_id, sort_order")) {
            if (projectId != null) {
                statement.setString(1, projectId);
            }
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    byProject.computeIfAbsent(row.getString("project_id"), id -> new ArrayList<>()).add(new Milestone(
                            row.getString("id"),
                            row.getString("name"),
                            row.getObject("start_date", LocalDate.class),
                            row.getObject("end_date", LocalDate.class),
                            row.getDouble("progress_time"),
                            List.of((String[]) row.getArray("assigned_employees").getArray()),
                            CompletionStatus.valueOf(row.getString("status"))));
                }
            }
        }
        return byProject;
    }

    private static Project toProject(ResultSet row, List<Milestone> milestones) throws SQLException {
        return new Project(
                row.getString("id"),
                row.getString("name"),
                row.getObject("start_date", LocalDate.class),
                row.getObject("end_date", LocalDate.class),
                row.getDouble("progress_time"),
                row.getString("manager"),
                row.getBigDecimal("budget"),
                CompletionStatus.valueOf(row.getString("status")),
                milestones);
    }
}
