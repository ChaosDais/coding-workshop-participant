package com.acme.services;

import com.acme.models.AuditEntry;
import com.acme.repos.InMemoryAuditRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditServiceTest {

    @Test
    void recordsEntriesNewestFirstWithDefaults() {
        AuditService auditService = new AuditService(new InMemoryAuditRepository(), Clock.systemUTC());

        AuditEntry first = auditService.recordAction("create", "alice", Map.of("resource", "order-42"));
        AuditEntry defaulted = auditService.recordAction(" ", (String) null, null);

        assertEquals(List.of(defaulted, first), auditService.getAuditEntries());
        assertEquals("unknown_action", defaulted.action());
        assertEquals("system", defaulted.actor());
        assertEquals(Map.of("resource", "order-42"), auditService.getAuditEntriesAsMaps().get(1).get("details"));
    }
}
