package com.acme.repos;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.function.Supplier;

/** Stores named values in the "app_settings" table. */
public class PostgresSettingsRepository implements SettingsRepository {
    private final PostgresDatabase database;

    public PostgresSettingsRepository(PostgresDatabase database) {
        this.database = database;
    }

    @Override
    public Optional<String> get(String name) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM app_settings WHERE name = ?")) {
                statement.setString(1, name);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? Optional.of(row.getString("value")) : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public void put(String name, String value) {
        database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO app_settings (name, value) VALUES (?, ?)"
                            + " ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value")) {
                statement.setString(1, name);
                statement.setString(2, value);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public String getOrCreate(String name, Supplier<String> initialValue) {
        return database.run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO app_settings (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")) {
                statement.setString(1, name);
                statement.setString(2, initialValue.get());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM app_settings WHERE name = ?")) {
                statement.setString(1, name);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return row.getString("value");
                }
            }
        });
    }
}
