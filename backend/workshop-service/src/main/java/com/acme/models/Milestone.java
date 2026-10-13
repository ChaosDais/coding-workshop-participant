package com.acme.models;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A blank id is replaced with a generated one; progressTime is in hours. */
public record Milestone(String id, String name, LocalDate startDate, LocalDate endDate, double progressTime,
                        List<String> assignedEmployees, CompletionStatus status) {
    public Milestone {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        name = Checks.text(name, "name");
        Checks.dateRange(startDate, endDate);
        Checks.nonNegative(progressTime, "progressTime");
        assignedEmployees = assignedEmployees == null ? List.of()
                : assignedEmployees.stream().filter(Objects::nonNull).map(String::trim)
                        .filter(employee -> !employee.isEmpty()).distinct().toList();
        status = status == null ? CompletionStatus.TODO : status;
    }
}
