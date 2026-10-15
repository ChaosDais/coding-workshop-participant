package com.acme.models;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Which audit entries to return, newest first. Every filter is optional; set ones must all match.
 *
 * @param projectId   entries about this project (its milestones and time included)
 * @param milestoneId entries about this milestone
 * @param actions     entries with one of these actions, e.g. project_created
 * @param from        entries on or after this day (UTC)
 * @param to          entries on or before this day (UTC)
 * @param text        entries whose action, actor or details contain this text, ignoring case
 * @param limit       at most this many entries, 1 to {@value #MAX_LIMIT}
 * @param offset      skip this many matching entries first, for pages after the first
 */
public record AuditQuery(String projectId, String milestoneId, List<String> actions, LocalDate from, LocalDate to,
                         String text, int limit, int offset) {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    public AuditQuery {
        projectId = blankToNull(projectId);
        milestoneId = blankToNull(milestoneId);
        actions = actions == null ? List.of()
                : actions.stream().filter(Objects::nonNull).map(String::trim).filter(a -> !a.isEmpty()).toList();
        text = blankToNull(text);
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    /** The first {@code limit} matching entries. */
    public AuditQuery(String projectId, String milestoneId, List<String> actions, LocalDate from, LocalDate to,
                      String text, int limit) {
        this(projectId, milestoneId, actions, from, to, text, limit, 0);
    }

    /** The most recent entries, without filters. */
    public static AuditQuery recent(int limit) {
        return new AuditQuery(null, null, null, null, null, null, limit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
