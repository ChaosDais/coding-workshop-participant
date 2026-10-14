package com.acme.models;

import com.acme.utils.Checks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A blank id is replaced with a generated one; progressTime is in hours; the description is optional free text.
 * assignedEmployees holds user ids. completedAt is set by the server when the milestone becomes COMPLETE.
 * dependsOn holds the ids of other milestones in the same project that must be complete before this one starts.
 */
public record Milestone(String id, String name, String description, LocalDate startDate, LocalDate endDate,
                        double progressTime, List<String> assignedEmployees, CompletionStatus status,
                        Instant completedAt, List<String> dependsOn) {
    public Milestone {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = Checks.text(name, "name");
        description = description == null || description.isBlank() ? null : description.trim();
        Checks.dateRange(startDate, endDate);
        Checks.nonNegative(progressTime, "progressTime");
        assignedEmployees = assignedEmployees == null ? List.of()
                : assignedEmployees.stream().filter(Objects::nonNull).map(String::trim)
                        .filter(employee -> !employee.isEmpty()).distinct().toList();
        status = status == null ? CompletionStatus.TODO : status;
        dependsOn = dependsOn == null ? List.of()
                : dependsOn.stream().filter(Objects::nonNull).map(String::trim)
                        .filter(dependency -> !dependency.isEmpty()).distinct().toList();
    }

    /** Whether work on the milestone has begun: anything past todo. */
    public boolean started() {
        return status != CompletionStatus.TODO;
    }

    public Milestone withStatus(CompletionStatus newStatus, Instant newCompletedAt) {
        return new Milestone(id, name, description, startDate, endDate, progressTime, assignedEmployees, newStatus,
                newCompletedAt, dependsOn);
    }

    public Milestone withProgress(double newProgressTime) {
        return new Milestone(id, name, description, startDate, endDate, newProgressTime, assignedEmployees, status,
                completedAt, dependsOn);
    }
}
