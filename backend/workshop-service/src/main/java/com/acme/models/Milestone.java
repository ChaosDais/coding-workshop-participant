package com.acme.models;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A blank id is replaced with a generated one; progressTime is in hours; the description is optional free text.
 * assignedEmployees holds user ids.
 */
public record Milestone(String id, String name, String description, LocalDate startDate, LocalDate endDate,
                        double progressTime, List<String> assignedEmployees, CompletionStatus status) {
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
    }
}
