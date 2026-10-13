package com.acme.services;

import com.acme.models.AuditEntry;
import com.acme.repos.InMemoryAuditStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditServiceTest {

    @Test
    void recordsEntriesNewestFirstWithDefaults() {
        AuditService auditService = new AuditService(new InMemoryAuditStore());

        AuditEntry first = auditService.recordAction("create", "alice", Map.of("resource", "order-42"));
        AuditEntry defaulted = auditService.recordAction(" ", null, null);

        assertEquals(List.of(defaulted, first), auditService.getAuditEntries());
        assertEquals("unknown_action", defaulted.action());
        assertEquals("system", defaulted.actor());
        assertEquals("order-42", auditService.getAuditEntriesAsMaps().get(1).get("details") instanceof Map<?, ?> details
                ? details.get("resource") : null);
    }
}
