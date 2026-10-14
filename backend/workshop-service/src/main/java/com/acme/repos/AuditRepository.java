package com.acme.repos;

import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;

import java.util.List;

/** Where audit entries are kept. */
public interface AuditRepository {
    void insert(AuditEntry entry);

    /** The entries matching the query, newest first. */
    List<AuditEntry> find(AuditQuery query);
}
