package com.acme.models;

import java.util.UUID;

/**
 * An account. A blank id is replaced with a generated one; only employees have a manager.
 * The password hash is kept by the store and is never part of this record, so it cannot leak into a response.
 */
public record User(String id, String email, String name, Role role, String managerId, boolean archived) {
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
    }

    public User withManager(String newManagerId) {
        return new User(id, email, name, role, newManagerId, archived);
    }
}
