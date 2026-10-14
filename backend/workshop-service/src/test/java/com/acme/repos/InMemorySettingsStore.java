package com.acme.repos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Test double for the PostgreSQL store. */
public class InMemorySettingsStore implements SettingsStore {
    private final Map<String, String> settings = new ConcurrentHashMap<>();

    @Override
    public String getOrCreate(String name, Supplier<String> initialValue) {
        return settings.computeIfAbsent(name, key -> initialValue.get());
    }
}
