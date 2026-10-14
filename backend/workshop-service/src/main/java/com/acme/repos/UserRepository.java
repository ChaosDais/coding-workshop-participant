package com.acme.repos;

import com.acme.models.Role;
import com.acme.models.User;

import java.util.List;
import java.util.Optional;

/** Where accounts and their password hashes are kept; users are archived, never deleted. */
public interface UserRepository {
    /** A user with the password hash needed to check a sign-in. */
    record Credentials(User user, String passwordHash) {
    }

    /** Fails when the id or email is already taken. */
    void insert(User user, String passwordHash);

    /** Inserts the user only while there are no users at all; returns false otherwise. */
    boolean insertIfEmpty(User user, String passwordHash);

    /** The user, archived or not. */
    Optional<User> findById(String id);

    /** The user with this (lowercased) email, archived or not. */
    Optional<Credentials> findByEmail(String email);

    /** Active or archived users, ordered by name. */
    List<User> findAll(boolean archived);

    /** Updates email, name, role, manager, job type and salary. */
    void update(User user);

    void setPasswordHash(String id, String passwordHash);

    /** Returns false when there is no active user with this id. */
    boolean archive(String id);

    /** Returns false when there is no archived user with this id. */
    boolean restore(String id);

    int countActive(Role role);

    /** Active employees whose manager is this user. */
    int countActiveEmployeesOf(String managerId);
}
