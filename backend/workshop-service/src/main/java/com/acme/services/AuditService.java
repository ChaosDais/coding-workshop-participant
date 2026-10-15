package com.acme.services;

import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;
import com.acme.models.User;
import com.acme.repos.AuditRepository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditService {
    static final int RECENT_LIMIT = 100;

    private final AuditRepository repository;
    private final Clock clock;

    public AuditService(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Records a change made by a signed-in user, keeping their name so the log reads by name, not by id. */
    public AuditEntry recordAction(String action, User actor, Map<String, Object> details) {
        Map<String, Object> named = new LinkedHashMap<>();
        if (details != null) {
            named.putAll(details);
        }
        named.put("actorName", actor.name());
        return recordAction(action, actor.email(), named);
    }

    public AuditEntry recordAction(String action, String actor, Map<String, Object> details) {
        String normalizedAction = action == null || action.isBlank() ? "unknown_action" : action;
        String normalizedActor = actor == null || actor.isBlank() ? "system" : actor;

        Map<String, Object> safeDetails = new LinkedHashMap<>();
        if (details != null) {
            safeDetails.putAll(details);
        }

        AuditEntry entry = new AuditEntry(normalizedAction, normalizedActor, safeDetails, clock.instant());
        repository.insert(entry);
        return entry;
    }

    /** Returns the most recent entries, newest first. */
    public List<AuditEntry> getAuditEntries() {
        return repository.find(AuditQuery.recent(RECENT_LIMIT));
    }

    public List<Map<String, Object>> getAuditEntriesAsMaps() {
        return getAuditEntries().stream().map(AuditEntry::asMap).toList();
    }

    /** The entries matching the query, newest first, in their response form. */
    public List<Map<String, Object>> find(AuditQuery query) {
        return repository.find(query).stream().map(AuditEntry::asMap).toList();
    }
}
