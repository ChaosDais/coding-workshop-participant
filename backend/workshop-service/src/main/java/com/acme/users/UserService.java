package com.acme.users;

import com.acme.audit.AuditService;
import com.acme.auth.PasswordHasher;
import com.acme.auth.TokenService;
import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.ProjectStore;
import com.acme.repos.UserStore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account management. Admins see and manage every account; managers can only look up the active employees on
 * their own team, without salaries. Users are archived, never deleted, and the rules keep the data consistent:
 * at least one active admin always remains, and a manager with active employees or projects cannot be archived
 * or given another role until those are moved to someone else.
 */
public class UserService {
    private final UserStore users;
    private final ProjectStore projects;
    private final PasswordHasher passwords;
    private final TokenService tokens;
    private final AuditService auditService;

    public UserService(UserStore users, ProjectStore projects, PasswordHasher passwords, TokenService tokens,
                       AuditService auditService) {
        this.users = users;
        this.projects = projects;
        this.passwords = passwords;
        this.tokens = tokens;
        this.auditService = auditService;
    }

    /** Admins see every account; managers see the active employees on their team. */
    public List<User> findAll(boolean archived, User actor) {
        if (actor.role() == Role.ADMIN) {
            return users.findAll(archived);
        }
        if (archived) {
            throw new ForbiddenException("Access denied");
        }
        return users.findAll(false).stream()
                .filter(user -> actor.id().equals(user.managerId()))
                .map(User::withoutSalary)
                .toList();
    }

    /** Admins can look up any account, archived or not; managers only active employees on their team. */
    public User require(String id, User actor) {
        User user = require(id);
        if (actor.role() == Role.ADMIN) {
            return user;
        }
        if (user.archived() || !actor.id().equals(user.managerId())) {
            throw new ForbiddenException("Access denied");
        }
        return user.withoutSalary();
    }

    private User require(String id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }

    public User create(User user, String password, User actor) {
        User created = user.withArchived(false);
        requireActiveManagerIfSet(created.managerId());
        users.insert(created, passwords.hash(password));
        audit("user_created", created, actor, Map.of("role", created.role().name()));
        return created;
    }

    /** Replaces email, name, role, manager, job type and salary. */
    public User update(String id, User changes, User actor) {
        User existing = requireActive(id);
        // PUT replaces the account's details, so a jobType or salary left out of the body is cleared.
        User updated = new User(id, changes.email(), changes.name(), changes.role(), changes.managerId(),
                changes.jobType(), changes.salary(), false);
        if (existing.role() != updated.role()) {
            requireRoleCanChange(existing);
        }
        if (updated.managerId() != null && !updated.managerId().equals(existing.managerId())) {
            requireActiveManagerIfSet(updated.managerId());
        }
        users.update(updated);
        audit("user_updated", updated, actor, Map.of("role", updated.role().name()));
        return updated;
    }

    /** Moves an employee to another manager, or to none when managerId is null. */
    public User moveEmployee(String id, String managerId, User actor) {
        User employee = requireActive(id);
        if (employee.role() != Role.EMPLOYEE) {
            throw new IllegalArgumentException("only employees can be moved between managers");
        }
        requireActiveManagerIfSet(managerId);
        User moved = employee.withManager(managerId);
        users.update(moved);
        Map<String, Object> details = new HashMap<>();
        details.put("fromManagerId", employee.managerId());
        details.put("toManagerId", managerId);
        audit("employee_moved", moved, actor, details);
        return moved;
    }

    /** Sets a new password and signs the user out of every session. */
    public void resetPassword(String id, String password, User actor) {
        User user = requireActive(id);
        users.setPasswordHash(id, passwords.hash(password));
        tokens.revokeAllRefreshTokens(id);
        audit("password_reset", user, actor, Map.of());
    }

    /** Archived users cannot sign in, and their existing tokens stop working at once. */
    public void archive(String id, User actor) {
        User user = requireActive(id);
        if (user.id().equals(actor.id())) {
            throw new ConflictException("You cannot archive your own account");
        }
        requireRoleCanChange(user);
        if (!users.archive(id)) {
            throw new NotFoundException("User " + id + " not found");
        }
        tokens.revokeAllRefreshTokens(id);
        audit("user_archived", user, actor, Map.of());
    }

    public User restore(String id, User actor) {
        if (!users.restore(id)) {
            throw new NotFoundException("Archived user " + id + " not found");
        }
        User user = require(id);
        audit("user_restored", user, actor, Map.of());
        return user;
    }

    private User requireActive(String id) {
        User user = require(id);
        if (user.archived()) {
            throw new NotFoundException("User " + id + " not found");
        }
        return user;
    }

    /** Checks that taking this user out of their current role leaves nothing without an admin or manager. */
    private void requireRoleCanChange(User user) {
        if (user.role() == Role.ADMIN && users.countActive(Role.ADMIN) <= 1) {
            throw new ConflictException("At least one active admin must remain");
        }
        if (user.role() == Role.MANAGER) {
            if (users.countActiveEmployeesOf(user.id()) > 0) {
                throw new ConflictException("Move this manager's employees to another manager first");
            }
            if (projects.findAll(false).stream().anyMatch(project -> user.id().equals(project.manager()))) {
                throw new ConflictException("Give this manager's projects to another manager first");
            }
        }
    }

    private void requireActiveManagerIfSet(String managerId) {
        if (managerId == null) {
            return;
        }
        boolean activeManager = users.findById(managerId)
                .filter(manager -> !manager.archived() && manager.role() == Role.MANAGER)
                .isPresent();
        if (!activeManager) {
            throw new IllegalArgumentException("managerId must be the id of an active manager: " + managerId);
        }
    }

    private void audit(String action, User subject, User actor, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", subject.id());
        details.put("email", subject.email());
        details.putAll(extra);
        auditService.recordAction(action, actor.email(), details);
    }
}
