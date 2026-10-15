package com.acme.models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A project as one viewer is allowed to see it. Its {@code progressTime} is the sum of its active milestones'
 * progress. Admins and managers get everything plus {@code spent}, the cost of all logged time. Employees get every
 * milestone, so they can see what their work depends on, but no money figures, since budget and spending would
 * reveal what colleagues earn.
 * {@code hoursByMilestone} is the time logged on each milestone by each person, by milestone id and then user id:
 * everyone's for admins and managers, only their own for employees.
 */
public record ProjectView(String id, String name, String description, LocalDate startDate, LocalDate endDate,
                         double progressTime, Double expectedHours, String manager, BigDecimal budget, BigDecimal spent,
                         CompletionStatus status, Instant completedAt, Instant statusChangedAt, List<String> dependsOn,
                         List<Dependency> dependencies, List<Milestone> milestones,
                         Map<String, Map<String, BigDecimal>> hoursByMilestone) {

    /**
     * A project this one depends on, named so every viewer can tell whether it is complete, even one who cannot
     * open that project.
     */
    public record Dependency(String id, String name, CompletionStatus status, boolean archived) {
    }

    public static ProjectView full(Project project, BigDecimal spent, List<Dependency> dependencies,
                                   Map<String, Map<String, BigDecimal>> hoursByMilestone) {
        return new ProjectView(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(), milestoneProgress(project), project.expectedHours(), project.manager(),
                project.budget(), spent,
                project.status(), project.completedAt(), project.statusChangedAt(), project.dependsOn(), dependencies,
                project.milestones(), hoursByMilestone);
    }

    public static ProjectView forEmployee(Project project, List<Dependency> dependencies,
                                          Map<String, Map<String, BigDecimal>> ownHoursByMilestone) {
        return new ProjectView(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(), milestoneProgress(project), project.expectedHours(), project.manager(), null, null,
                project.status(),
                project.completedAt(), project.statusChangedAt(), project.dependsOn(), dependencies,
                project.milestones(), ownHoursByMilestone);
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
