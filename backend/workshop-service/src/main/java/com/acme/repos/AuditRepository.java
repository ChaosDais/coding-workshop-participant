package com.acme.repos;

import com.acme.models.AuditEntry;
import com.acme.models.AuditQuery;

import java.util.List;

/** Where audit entries are kept. */
public interface AuditRepository {
    void insert(AuditEntry entry);

    /** The entries matching the query, newest first, from its offset. */
    List<AuditEntry> find(AuditQuery query);

    /** How many entries match the query's filters, ignoring its limit and offset. */
    int count(AuditQuery query);
}
