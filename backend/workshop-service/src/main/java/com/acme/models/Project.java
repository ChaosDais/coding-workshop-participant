package com.acme.models;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A blank id is replaced with a generated one; progressTime is in hours. */
public record Project(String id, String name, LocalDate startDate, LocalDate endDate, double progressTime,
                      String manager, BigDecimal budget, CompletionStatus status, List<Milestone> milestones) {
    public Project {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = Checks.text(name, "name");
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
}
