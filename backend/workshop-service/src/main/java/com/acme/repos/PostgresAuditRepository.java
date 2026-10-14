package com.acme.repos;

import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stores each audit entry as one row in the "audit_entries" table, with its details as JSON. */
public class PostgresAuditRepository implements AuditRepository {
    private static final Gson GSON = new Gson();
    private static final Type DETAILS_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final PostgresDatabase database;

    public PostgresAuditRepository(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(AuditEntry entry) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO audit_entries (id, action, actor, details, occurred_at)"
                            + " VALUES (?, ?, ?, CAST(? AS JSONB), ?)")) {
                statement.setString(1, entry.id());
                statement.setString(2, entry.action());
                statement.setString(3, entry.actor());
                statement.setString(4, GSON.toJson(entry.details()));
                statement.setObject(5, entry.timestamp().atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<AuditEntry> find(AuditQuery query) {
        List<String> conditions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (query.projectId() != null) {
            conditions.add("details ->> 'projectId' = ?");
            values.add(query.projectId());
        }
        if (query.milestoneId() != null) {
            conditions.add("details ->> 'milestoneId' = ?");
            values.add(query.milestoneId());
        }
        if (query.from() != null) {
            conditions.add("occurred_at >= ?");
            values.add(query.from().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (query.to() != null) {
            conditions.add("occurred_at < ?");
            values.add(query.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (query.text() != null) {
            conditions.add("(action ILIKE ? OR actor ILIKE ? OR details::text ILIKE ?)");
            String pattern = "%" + query.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            values.add(pattern);
            values.add(pattern);
            values.add(pattern);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        if (!query.actions().isEmpty()) {
            where += (where.isEmpty() ? " WHERE " : " AND ") + "action = ANY (?)";
        }
        String sql = "SELECT id, action, actor, details, occurred_at FROM audit_entries" + where
                + " ORDER BY occurred_at DESC LIMIT ?";
        return database.run(connection -> {
            List<AuditEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (Object value : values) {
                    statement.setObject(index++, value);
                }
                if (!query.actions().isEmpty()) {
                    statement.setArray(index++, connection.createArrayOf("text", query.actions().toArray()));
                }
                statement.setInt(index, query.limit());
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        entries.add(new AuditEntry(
                                row.getString("id"),
                                row.getString("action"),
                                row.getString("actor"),
                                GSON.fromJson(row.getString("details"), DETAILS_TYPE),
                                row.getObject("occurred_at", OffsetDateTime.class).toInstant()));
                    }
                }
            }
            return entries;
        });
    }
}
