package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.Role;
import com.acme.models.User;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Stores accounts in the "users" table; archived users keep their row with archived_at set. */
public class PostgresUserStore implements UserStore {
    private static final String COLUMNS = "id, email, name, role, manager_id, archived_at IS NOT NULL AS archived";
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresDatabase database;

    public PostgresUserStore(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public void insert(User user, String passwordHash) {
        database.run(connection -> {
            insert(connection.prepareStatement(
                    "INSERT INTO users (id, email, name, role, manager_id, password_hash) VALUES (?, ?, ?, ?, ?, ?)"),
                    user, passwordHash);
            return null;
        });
    }

    @Override
    public boolean insertIfEmpty(User user, String passwordHash) {
        return database.run(connection -> {
            // The lock makes two simultaneous first sign-ups queue up, so only one of them finds the table empty.
            try (Statement statement = connection.createStatement()) {
                statement.execute("LOCK TABLE users IN EXCLUSIVE MODE");
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM users LIMIT 1");
                 ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    return false;
                }
            }
            insert(connection.prepareStatement(
                    "INSERT INTO users (id, email, name, role, manager_id, password_hash) VALUES (?, ?, ?, ?, ?, ?)"),
                    user, passwordHash);
            return true;
        });
    }

    @Override
    public Optional<User> findById(String id) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM users WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(toUser(row)) : Optional.<User>empty();
                }
            }
        });
    }

    @Override
    public Optional<Credentials> findByEmail(String email) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + ", password_hash FROM users WHERE email = ?")) {
                statement.setString(1, email);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            ? Optional.of(new Credentials(toUser(row), row.getString("password_hash")))
                            : Optional.<Credentials>empty();
                }
            }
        });
    }

    @Override
    public List<User> findAll(boolean archived) {
        return database.run(connection -> {
            List<User> users = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM users WHERE archived_at IS " + (archived ? "NOT NULL" : "NULL")
                            + " ORDER BY name, email");
                 ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    users.add(toUser(row));
                }
            }
            return users;
        });
    }

    @Override
    public void update(User user) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET email = ?, name = ?, role = ?, manager_id = ? WHERE id = ?")) {
                statement.setString(1, user.email());
                statement.setString(2, user.name());
                statement.setString(3, user.role().name());
                statement.setString(4, user.managerId());
                statement.setString(5, user.id());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw duplicateEmailOr(e, user);
            }
            return null;
        });
    }

    @Override
    public void setPasswordHash(String id, String passwordHash) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET password_hash = ? WHERE id = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean archive(String id) {
        return updateCount("UPDATE users SET archived_at = now() WHERE id = ? AND archived_at IS NULL", id) > 0;
    }

    @Override
    public boolean restore(String id) {
        return updateCount("UPDATE users SET archived_at = NULL WHERE id = ? AND archived_at IS NOT NULL", id) > 0;
    }

    @Override
    public int countActive(Role role) {
        return count("SELECT count(*) FROM users WHERE role = ? AND archived_at IS NULL", role.name());
    }

    @Override
    public int countActiveEmployeesOf(String managerId) {
        return count("SELECT count(*) FROM users WHERE manager_id = ? AND archived_at IS NULL", managerId);
    }

    private static void insert(PreparedStatement statement, User user, String passwordHash) throws SQLException {
        try (statement) {
            statement.setString(1, user.id());
            statement.setString(2, user.email());
            statement.setString(3, user.name());
            statement.setString(4, user.role().name());
            statement.setString(5, user.managerId());
            statement.setString(6, passwordHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw duplicateEmailOr(e, user);
        }
    }

    /** Turns a unique violation into a 409; the transaction is rolled back either way. */
    private static RuntimeException duplicateEmailOr(SQLException e, User user) throws SQLException {
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
            throw new ConflictException("A user with email " + user.email() + " or id " + user.id()
                    + " already exists");
        }
        throw e;
    }

    private int updateCount(String sql, String id) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                return statement.executeUpdate();
            }
        });
    }

    private int count(String sql, String value) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, value);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return row.getInt(1);
                }
            }
        });
    }

    private static User toUser(ResultSet row) throws SQLException {
        return new User(row.getString("id"), row.getString("email"), row.getString("name"),
                Role.valueOf(row.getString("role")), row.getString("manager_id"), row.getBoolean("archived"));
    }
}
