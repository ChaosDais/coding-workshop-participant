package com.acme.repos;

import com.acme.models.AuditEntry;

import java.util.List;

/** Where audit entries are kept. */
public interface AuditRepository {
    void insert(AuditEntry entry);

    /** Returns at most {@code limit} entries, newest first. */
    List<AuditEntry> findRecent(int limit);
}
