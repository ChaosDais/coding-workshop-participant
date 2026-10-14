package com.acme.models;

import com.acme.utils.Checks;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An account. A blank id is replaced with a generated one; only employees have a manager.
 * Job type and salary are optional, since accounts created before they existed have neither.
 * The password hash is kept by the repository and is never part of this record, so it cannot leak into a response.
 */
public record User(String id, String email, String name, Role role, String managerId, String jobType,
                   BigDecimal salary, boolean archived) {
    public User {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        email = Checks.email(email);
        name = Checks.text(name, "name");
        if (role == null) {
            throw new IllegalArgumentException("role must be one of ADMIN, MANAGER, EMPLOYEE");
        }
        managerId = managerId == null || managerId.isBlank() ? null : managerId.trim();
        if (managerId != null && role != Role.EMPLOYEE) {
            throw new IllegalArgumentException("only employees can have a manager");
        }
        jobType = jobType == null ? null : Checks.text(jobType, "jobType");
        if (salary != null && salary.signum() < 0) {
            throw new IllegalArgumentException("salary must be a non-negative number");
        }
    }

    public User withManager(String newManagerId) {
        return new User(id, email, name, role, newManagerId, jobType, salary, archived);
    }

    /** The same account without its salary, for viewers who may not see pay. */
    public User withoutSalary() {
        return new User(id, email, name, role, managerId, jobType, null, archived);
    }

    public User withArchived(boolean newArchived) {
        return new User(id, email, name, role, managerId, jobType, salary, newArchived);
    }
}
