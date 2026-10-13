package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRepositoryTest {
    private static Project sampleProject(String id) {
        Milestone design = new Milestone("m1", "Design", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), 4.5,
                List.of("bob", "carol"), CompletionStatus.IN_PROGRESS);
        Milestone build = new Milestone("m2", "Build", LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 1), 0,
                List.of(), CompletionStatus.TODO);
        return new Project(id, "Apollo", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 12.5, "alice",
                new BigDecimal("50000.75"), CompletionStatus.TODO, List.of(design, build));
    }

    @Test
    void documentMappingSurvivesBsonRoundTrip() {
        Project project = sampleProject("p1");

        // Extended JSON mimics what the wire format preserves (BSON dates, Decimal128).
        Document stored = Document.parse(ProjectRepository.toDocument(project).toJson());

        assertEquals(project, ProjectRepository.fromDocument(stored));
    }

    @Test
    void invalidModelsAreRejected() {
        Project valid = sampleProject("p1");

        assertThrows(IllegalArgumentException.class, () -> new Project("p", " ", valid.startDate(), valid.endDate(),
                0, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", valid.endDate(), valid.startDate(),
                0, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", valid.startDate(), valid.endDate(),
                0, "alice", new BigDecimal("-1"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", valid.startDate(), valid.endDate(),
                -1, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", valid.startDate(), valid.endDate(),
                0, "alice", BigDecimal.ONE, null, List.of(valid.milestones().get(0), valid.milestones().get(0))));
    }

    @Test
    void defaultsAreApplied() {
        Project project = new Project(null, "n", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 0, "m",
                BigDecimal.ZERO, null, null);

        assertFalse(project.id().isBlank());
        assertEquals(CompletionStatus.TODO, project.status());
        assertTrue(project.milestones().isEmpty());
    }

    /** Runs against the MongoDB (Atlas) deployment named by MONGODB_URI; skipped when it is not set. */
    @Test
    void crudAgainstMongoDb() {
        String uri = System.getenv("MONGODB_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "MONGODB_URI is not set");

        String database = "workshop_test_" + UUID.randomUUID().toString().replace("-", "");
        try (MongoClient client = ProjectRepository.createClient(uri)) {
            try {
                ProjectRepository repository = new ProjectRepository(client, database);
                Project project = sampleProject("p1");

                repository.insert(project);
                assertThrows(MongoWriteException.class, () -> repository.insert(project));
                assertEquals(project, repository.findById("p1").orElseThrow());
                assertEquals(List.of(project), repository.findAll());

                Project updated = new Project("p1", "Gemini", project.startDate(), project.endDate(), 20, "dave",
                        new BigDecimal("1.00"), CompletionStatus.COMPLETE, List.of(project.milestones().get(1)));
                assertTrue(repository.replace(updated));
                assertEquals(updated, repository.findById("p1").orElseThrow());
                assertFalse(repository.replace(sampleProject("missing")));

                assertTrue(repository.deleteById("p1"));
                assertFalse(repository.deleteById("p1"));
                assertTrue(repository.findById("p1").isEmpty());
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
