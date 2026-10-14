package com.acme.repos;

import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Test double for the PostgreSQL repository, with the same filters; returns entries newest first. */
public class InMemoryAuditRepository implements AuditRepository {
    private final List<AuditEntry> entries = new ArrayList<>();

    @Override
    public synchronized void insert(AuditEntry entry) {
        entries.add(0, entry);
    }

    @Override
    public synchronized List<AuditEntry> find(AuditQuery query) {
        return entries.stream()
                .filter(entry -> query.projectId() == null
                        || query.projectId().equals(entry.details().get("projectId")))
                .filter(entry -> query.milestoneId() == null
                        || query.milestoneId().equals(entry.details().get("milestoneId")))
                .filter(entry -> query.actions().isEmpty() || query.actions().contains(entry.action()))
                .filter(entry -> query.from() == null || !day(entry).isBefore(query.from()))
                .filter(entry -> query.to() == null || !day(entry).isAfter(query.to()))
                .filter(entry -> query.text() == null || contains(entry, query.text()))
                .limit(query.limit())
                .toList();
    }

    private static LocalDate day(AuditEntry entry) {
        return entry.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static boolean contains(AuditEntry entry, String text) {
        String needle = text.toLowerCase();
        return entry.action().toLowerCase().contains(needle) || entry.actor().toLowerCase().contains(needle)
                || entry.details().toString().toLowerCase().contains(needle);
    }
}
