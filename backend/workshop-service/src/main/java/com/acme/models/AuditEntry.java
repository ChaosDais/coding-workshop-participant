package com.acme.models;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(String id, String action, String actor, Map<String, Object> details, Instant timestamp) {
    public AuditEntry(String action, String actor, Map<String, Object> details, Instant at) {
        // Millisecond precision is what the database keeps, so a stored entry reads back equal.
        this(UUID.randomUUID().toString(), action, actor, new LinkedHashMap<>(details == null ? Map.of() : details),
                at.truncatedTo(ChronoUnit.MILLIS));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("id", id);
        audit.put("action", action);
        audit.put("actor", actor);
        audit.put("timestamp", timestamp.toString());
        audit.put("details", details);
        return audit;
    }
}
