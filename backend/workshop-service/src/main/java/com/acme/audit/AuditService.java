package com.acme.audit;

import com.acme.models.AuditEntry;
import com.acme.repos.AuditStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditService {
    static final int RECENT_LIMIT = 100;

    private final AuditStore store;

    public AuditService(AuditStore store) {
        this.store = store;
    }

    public AuditEntry recordAction(String action, String actor, Map<String, Object> details) {
        String normalizedAction = action == null || action.isBlank() ? "unknown_action" : action;
        String normalizedActor = actor == null || actor.isBlank() ? "system" : actor;

        Map<String, Object> safeDetails = new LinkedHashMap<>();
        if (details != null) {
            safeDetails.putAll(details);
        }

        AuditEntry entry = new AuditEntry(normalizedAction, normalizedActor, safeDetails);
        store.insert(entry);
        return entry;
    }

    /** Returns the most recent entries, newest first. */
    public List<AuditEntry> getAuditEntries() {
        return store.findRecent(RECENT_LIMIT);
    }

    public List<Map<String, Object>> getAuditEntriesAsMaps() {
        return getAuditEntries().stream().map(AuditEntry::asMap).toList();
    }
}
