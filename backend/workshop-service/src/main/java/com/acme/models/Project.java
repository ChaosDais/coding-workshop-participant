package com.acme.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A blank id is replaced with a generated one; progressTime is in hours; the description is optional free text.
 * manager holds a user id. completedAt is set by the server when the project becomes COMPLETE.
 */
public record Project(String id, String name, String description, LocalDate startDate, LocalDate endDate,
                      double progressTime, String manager, BigDecimal budget, CompletionStatus status,
                      List<Milestone> milestones, Instant completedAt) {
    public Project {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = Checks.text(name, "name");
        description = description == null || description.isBlank() ? null : description.trim();
        Checks.dateRange(startDate, endDate);
        Checks.nonNegative(progressTime, "progressTime");
        manager = Checks.text(manager, "manager");
        if (budget == null || budget.signum() < 0) {
            throw new IllegalArgumentException("budget must be a non-negative number");
        }
        status = status == null ? CompletionStatus.TODO : status;
        milestones = milestones == null ? List.of() : List.copyOf(milestones);

        Set<String> milestoneIds = new HashSet<>();
        for (Milestone milestone : milestones) {
            if (!milestoneIds.add(milestone.id())) {
                throw new IllegalArgumentException("Duplicate milestone id: " + milestone.id());
            }
        }
    }

    public Project withMilestones(List<Milestone> newMilestones) {
        return new Project(id, name, description, startDate, endDate, progressTime, manager, budget, status,
                newMilestones, completedAt);
    }

    public Project withCompletedAt(Instant newCompletedAt) {
        return new Project(id, name, description, startDate, endDate, progressTime, manager, budget, status,
                milestones, newCompletedAt);
    }
}
