package com.acme.repos;

import com.acme.models.AuditEntry;

import java.util.ArrayList;
import java.util.List;

/** Test double for the PostgreSQL repository; returns entries newest first. */
public class InMemoryAuditRepository implements AuditRepository {
    private final List<AuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized void insert(AuditEntry entry) {
        entries.add(0, entry);
    }

    @Override
    public synchronized List<AuditEntry> findRecent(int limit) {
        return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
    }
}
