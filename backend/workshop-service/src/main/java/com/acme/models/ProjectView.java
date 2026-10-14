package com.acme.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A project as one viewer is allowed to see it. Its {@code progressTime} is the sum of its active milestones'
 * progress. Admins and managers get everything plus {@code spent}, the cost of all logged time. Employees get every
 * milestone, so they can see what their work depends on, but no money figures, since budget and spending would
 * reveal what colleagues earn.
 */
public record ProjectView(String id, String name, String description, LocalDate startDate, LocalDate endDate,
                         double progressTime, String manager, BigDecimal budget, BigDecimal spent,
                         CompletionStatus status, Instant completedAt, List<Milestone> milestones) {

    public static ProjectView full(Project project, BigDecimal spent) {
        return new ProjectView(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(), milestoneProgress(project), project.manager(), project.budget(), spent,
                project.status(), project.completedAt(), project.milestones());
    }

    public static ProjectView forEmployee(Project project) {
        return new ProjectView(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(), milestoneProgress(project), project.manager(), null, null, project.status(),
                project.completedAt(), project.milestones());
    }

    /** Hours across all the project's active milestones, rounded to hundredths. */
    private static double milestoneProgress(Project project) {
        return project.milestones().stream()
                .map(milestone -> BigDecimal.valueOf(milestone.progressTime()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
