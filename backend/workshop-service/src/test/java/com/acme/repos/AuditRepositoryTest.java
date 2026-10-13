package com.acme.repos;

import com.acme.models.AuditEntry;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditRepositoryTest {

    @Test
    void documentMappingSurvivesBsonRoundTrip() {
        AuditEntry entry = new AuditEntry("project_created", "alice", Map.of("projectId", "p1", "name", "Apollo"));

        // Extended JSON mimics what the wire format preserves (BSON dates).
        Document stored = Document.parse(AuditRepository.toDocument(entry).toJson());

        assertEquals(entry, AuditRepository.fromDocument(stored));
    }
}
