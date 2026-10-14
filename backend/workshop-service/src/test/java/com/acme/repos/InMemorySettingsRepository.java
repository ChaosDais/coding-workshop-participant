package com.acme.repos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Test double for the PostgreSQL repository. */
public class InMemorySettingsRepository implements SettingsRepository {
    private final Map<String, String> settings = new ConcurrentHashMap<>();

    @Override
    public String getOrCreate(String name, Supplier<String> initialValue) {
        return settings.computeIfAbsent(name, key -> initialValue.get());
    }
}
