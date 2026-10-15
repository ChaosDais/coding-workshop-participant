package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.DailyHours;
import com.acme.models.TimeEntry;

import java.math.BigDecimal;
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

/** Stores time in the "time_entries" table and keeps milestone progress in step with it. */
public class PostgresTimeEntryRepository implements TimeEntryRepository {
    private static final String COLUMNS = "id, project_id, milestone_id, user_id, source, note, started_at, ended_at,"
            + " hours, hourly_rate, cost, logged_at, corrected_at, corrected_by, correction_reason";
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresDatabase database;

    public PostgresTimeEntryRepository(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insertLogged(TimeEntry entry) {
        database.run(connection -> {
            insert(connection, entry);
            addProgress(connection, entry.projectId(), entry.milestoneId(), entry.hours());
            return null;
        });
    }

    @Override
    public void insertRunning(TimeEntry entry) {
        database.run(connection -> {
            try {
                insert(connection, entry);
            } catch (SQLException e) {
                if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    throw new ConflictException("A timer is already running; stop it before starting another");
                }
                throw e;
            }
            return null;
        });
    }

    @Override
    public Optional<TimeEntry> findRunning(String userId) {
        List<TimeEntry> found = query("SELECT " + COLUMNS + " FROM time_entries"
                + " WHERE user_id = ? AND source = 'TIMER' AND ended_at IS NULL", userId);
        return found.stream().findFirst();
    }

    @Override
    public Optional<TimeEntry> findById(String id) {
        return query("SELECT " + COLUMNS + " FROM time_entries WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public boolean correct(TimeEntry corrected, BigDecimal previousHours) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE time_entries SET hours = ?, cost = ?, note = ?, corrected_at = ?, corrected_by = ?,"
                            + " correction_reason = ? WHERE id = ? AND hours = ?")) {
                statement.setBigDecimal(1, corrected.hours());
                statement.setBigDecimal(2, corrected.cost());
                statement.setString(3, corrected.note());
                statement.setObject(4, utc(corrected.correctedAt()));
                statement.setString(5, corrected.correctedBy());
                statement.setString(6, corrected.correctionReason());
                statement.setString(7, corrected.id());
                statement.setBigDecimal(8, previousHours);
                if (statement.executeUpdate() == 0) {
                    return false;
                }
            }
            addProgress(connection, corrected.projectId(), corrected.milestoneId(),
                    corrected.hours().subtract(previousHours));
            return true;
        });
    }

    @Override
    public boolean stop(TimeEntry finished) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE time_entries SET ended_at = ?, hours = ?, hourly_rate = ?, cost = ?, logged_at = ?"
                            + " WHERE id = ? AND ended_at IS NULL")) {
                statement.setObject(1, utc(finished.endedAt()));
                statement.setBigDecimal(2, finished.hours());
                statement.setBigDecimal(3, finished.hourlyRate());
                statement.setBigDecimal(4, finished.cost());
                statement.setObject(5, utc(finished.loggedAt()));
                statement.setString(6, finished.id());
                if (statement.executeUpdate() == 0) {
                    return false;
                }
            }
            addProgress(connection, finished.projectId(), finished.milestoneId(), finished.hours());
            return true;
        });
    }

    @Override
    public List<TimeEntry> findForMilestone(String projectId, String milestoneId) {
        return query("SELECT " + COLUMNS + " FROM time_entries WHERE project_id = ? AND milestone_id = ?"
                + " ORDER BY coalesce(started_at, logged_at), logged_at", projectId, milestoneId);
    }

    @Override
    public List<TimeEntry> findLoggedForUser(String userId) {
        return query("SELECT " + COLUMNS + " FROM time_entries WHERE user_id = ? AND hours IS NOT NULL"
                + " ORDER BY logged_at", userId);
    }

    @Override
    public List<DailyHours> dailyHours() {
        return database.run(connection -> {
            List<DailyHours> days = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id, project_id, milestone_id,"
                            + " (coalesce(started_at, logged_at) AT TIME ZONE 'UTC')::date AS day, sum(hours) AS hours"
                            + " FROM time_entries WHERE hours IS NOT NULL"
                            + " GROUP BY user_id, project_id, milestone_id, day ORDER BY day");
                 ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    days.add(new DailyHours(row.getString("user_id"), row.getString("project_id"),
                            row.getString("milestone_id"), row.getObject("day", LocalDate.class),
                            row.getBigDecimal("hours")));
                }
            }
            return days;
        });
    }

    @Override
    public Map<String, BigDecimal> totalCostByProject() {
        return database.run(connection -> {
            Map<String, BigDecimal> totals = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT project_id, sum(cost) AS total FROM time_entries WHERE cost IS NOT NULL"
                            + " GROUP BY project_id");
                 ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    totals.put(row.getString("project_id"), row.getBigDecimal("total"));
                }
            }
            return totals;
        });
    }

    private static void insert(Connection connection, TimeEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO time_entries (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.projectId());
            statement.setString(3, entry.milestoneId());
            statement.setString(4, entry.userId());
            statement.setString(5, entry.source().name());
            statement.setString(6, entry.note());
            statement.setObject(7, utc(entry.startedAt()));
            statement.setObject(8, utc(entry.endedAt()));
            statement.setBigDecimal(9, entry.hours());
            statement.setBigDecimal(10, entry.hourlyRate());
            statement.setBigDecimal(11, entry.cost());
            statement.setObject(12, utc(entry.loggedAt()));
            statement.setObject(13, utc(entry.correctedAt()));
            statement.setString(14, entry.correctedBy());
            statement.setString(15, entry.correctionReason());
            statement.executeUpdate();
        }
    }

    /**
     * Adds (or, for a correction, subtracts) hours in the database itself, so concurrent logs and edits cannot lose
     * an update. Rounded to hundredths so repeated additions do not drift; never below zero.
     */
    private static void addProgress(Connection connection, String projectId, String milestoneId, BigDecimal hours)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE milestones SET progress_time = greatest(round((progress_time + ?)::numeric, 2), 0)"
                        + " WHERE project_id = ? AND id = ?")) {
            statement.setBigDecimal(1, hours);
            statement.setString(2, projectId);
            statement.setString(3, milestoneId);
            statement.executeUpdate();
        }
    }

    private List<TimeEntry> query(String sql, String... values) {
        return database.run(connection -> {
            List<TimeEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < values.length; index++) {
                    statement.setString(index + 1, values[index]);
                }
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        entries.add(new TimeEntry(row.getString("id"), row.getString("project_id"),
                                row.getString("milestone_id"), row.getString("user_id"),
                                TimeEntry.Source.valueOf(row.getString("source")), row.getString("note"),
                                instant(row, "started_at"), instant(row, "ended_at"), row.getBigDecimal("hours"),
                                row.getBigDecimal("hourly_rate"), row.getBigDecimal("cost"),
                                instant(row, "logged_at"), instant(row, "corrected_at"),
                                row.getString("corrected_by"), row.getString("correction_reason")));
                    }
                }
            }
            return entries;
        });
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
