package com.acme.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What one person works on and has logged time on: by project, then milestone. Milestones they are assigned to are
 * listed even without time; milestones they logged time on are listed even after being unassigned or archived.
 */
public record TimeContributions(String userId, BigDecimal totalHours, List<ProjectContribution> projects) {

    public record ProjectContribution(String projectId, String projectName, CompletionStatus status,
                                      boolean archived, BigDecimal hours, List<MilestoneContribution> milestones) {
    }

    /** {@code lastLoggedAt} is null when nothing has been logged yet. */
    public record MilestoneContribution(String milestoneId, String milestoneName, CompletionStatus status,
                                        boolean archived, boolean assigned, BigDecimal hours, int entries,
                                        Instant lastLoggedAt) {
    }
}
