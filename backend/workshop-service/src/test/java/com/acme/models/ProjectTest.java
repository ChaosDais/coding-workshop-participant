package com.acme.models;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTest {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Test
    void invalidModelsAreRejected() {
        Milestone milestone = new Milestone("m1", "Design", START, END, 4.5, List.of("bob"), null);

        assertThrows(IllegalArgumentException.class, () -> new Project("p", " ", START, END,
                0, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", END, START,
                0, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", START, END,
                0, "alice", new BigDecimal("-1"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", START, END,
                -1, "alice", BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Project("p", "n", START, END,
                0, "alice", BigDecimal.ONE, null, List.of(milestone, milestone)));
    }

    @Test
    void defaultsAreApplied() {
        Project project = new Project(null, "n", START, START, 0, "m", BigDecimal.ZERO, null, null);

        assertFalse(project.id().isBlank());
        assertEquals(CompletionStatus.TODO, project.status());
        assertTrue(project.milestones().isEmpty());
    }
}
