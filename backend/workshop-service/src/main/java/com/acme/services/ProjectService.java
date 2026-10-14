package com.acme.services;

import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.models.ProjectView;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.ProjectRepository;
import com.acme.repos.TimeEntryRepository;
import com.acme.repos.UserRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business rules for projects and their milestones; every change is recorded as an audit entry with the user
 * who made it.
 * <ul>
 *   <li>Admins can see and change every project, including archived ones.</li>
 *   <li>Managers can see and change only the active projects they manage, but cannot hand a project to another
 *       manager or change its description.</li>
 *   <li>Employees can see only the active projects where they are assigned to a milestone, with all of that
 *       project's milestones; the one change they can make is moving one of their own milestones to
 *       AWAITING_REVIEW.</li>
 * </ul>
 * Whoever makes the change, only employees on the project manager's team can be newly assigned to its milestones;
 * an employee moves to another team by an admin changing their manager.
 * A project's manager must be an active manager and assigned employees must be active employees; references are
 * checked when they change, so existing ones stay valid if a user is later archived or moved to another team.
 * Dates, completion times and milestone dependencies follow {@link ProjectRules} on every change.
 */
public class ProjectService {
    private final ProjectRepository projects;
    private final UserRepository users;
    private final TimeEntryRepository timeEntries;
    private final AuditService auditService;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, UserRepository users, TimeEntryRepository timeEntries,
                          AuditService auditService, Clock clock) {
        this.projects = projects;
        this.users = users;
        this.timeEntries = timeEntries;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Admins see every project; managers see the active projects they manage; employees see the active projects
     * where they are assigned to a milestone.
     */
    public List<ProjectView> findAll(boolean archived, User actor) {
        if (archived && actor.role() != Role.ADMIN) {
            throw new ForbiddenException("Access denied");
        }
        List<Project> visible = projects.findAll(archived).stream()
                .filter(project -> canSee(project, actor))
                .toList();
        Map<String, BigDecimal> spent = timeEntries.totalCostByProject();
        return visible.stream().map(project -> view(project, actor, spent)).toList();
    }

    public ProjectView get(String id, User actor) {
        return view(require(id, actor), actor);
    }

    /** The active project, if this user may see it. */
    Project require(String id, User actor) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
        if (!canSee(project, actor)) {
            throw new ForbiddenException("Access denied");
        }
        return project;
    }

    /** The active project, if this user may change it: admins any, managers their own. */
    private Project requireEditable(String id, User actor) {
        Project project = require(id, actor);
        if (actor.role() == Role.EMPLOYEE) {
            throw new ForbiddenException("Access denied");
        }
        return project;
    }

    private static boolean canSee(Project project, User actor) {
        return switch (actor.role()) {
            case ADMIN -> true;
            case MANAGER -> actor.id().equals(project.manager());
            case EMPLOYEE -> project.milestones().stream()
                    .anyMatch(milestone -> milestone.assignedEmployees().contains(actor.id()));
        };
    }

    ProjectView view(Project project, User actor) {
        return view(project, actor, timeEntries.totalCostByProject());
    }

    private static ProjectView view(Project project, User actor, Map<String, BigDecimal> spent) {
        return actor.role() == Role.EMPLOYEE
                ? ProjectView.forEmployee(project)
                : ProjectView.full(project, spent.getOrDefault(project.id(), BigDecimal.ZERO));
    }

    public ProjectView create(Project project, User actor) {
        // Archived projects keep their ids, so an id can never be reused.
        if (projects.exists(project.id())) {
            throw new ConflictException("A project with id " + project.id() + " already exists");
        }
        requireActiveUser(project.manager(), Role.MANAGER, "manager");
        project.milestones().forEach(milestone -> requireEmployees(milestone, Set.of(), project.manager()));
        Project created = ProjectRules.apply(null, project, clock.instant());
        projects.insert(created);
        audit("project_created", created, null, actor);
        return view(created, actor);
    }

    /** Applies the changes to the project; its milestones are kept unless {@code replaceMilestones} is set. */
    public ProjectView update(String id, Project changes, boolean replaceMilestones, User actor) {
        Project existing = requireEditable(id, actor);
        if (!changes.manager().equals(existing.manager())) {
            if (actor.role() != Role.ADMIN) {
                throw new ForbiddenException("Only admins can change a project's manager");
            }
            requireActiveUser(changes.manager(), Role.MANAGER, "manager");
        }
        String description = changes.description();
        if (actor.role() != Role.ADMIN) {
            // Managers may leave the description out, which keeps it, but only admins can change it.
            if (description == null) {
                description = existing.description();
            } else if (!description.equals(existing.description())) {
                throw new ForbiddenException("Only admins can change a project's description");
            }
        }
        if (replaceMilestones) {
            // Checked against the manager the project has after this update.
            changes.milestones().forEach(milestone ->
                    requireEmployees(milestone, assignedBefore(existing, milestone), changes.manager()));
        }
        Project updated = ProjectRules.apply(existing, new Project(id, changes.name(), description,
                changes.startDate(), changes.endDate(), changes.progressTime(), changes.manager(), changes.budget(),
                changes.status(), replaceMilestones ? changes.milestones() : existing.milestones(), null),
                clock.instant());
        save(updated);
        audit("project_updated", updated, null, actor);
        return view(updated, actor);
    }

    /** Hides the project from the active views; it stays stored and can be restored. */
    public void archive(String id, User actor) {
        Project project = requireEditable(id, actor);
        if (!projects.archive(id)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Project " + id + " not found");
        }
        audit("project_archived", project, null, actor);
    }

    public ProjectView restore(String id, User actor) {
        if (!projects.restore(id)) {
            throw new NotFoundException("Archived project " + id + " not found");
        }
        Project project = require(id, actor);
        audit("project_restored", project, null, actor);
        return view(project, actor);
    }

    public Milestone addMilestone(String projectId, Milestone milestone, User actor) {
        Project project = requireEditable(projectId, actor);
        if (project.milestones().stream().anyMatch(existing -> existing.id().equals(milestone.id()))) {
            throw new ConflictException("A milestone with id " + milestone.id() + " already exists");
        }
        requireEmployees(milestone, Set.of(), project.manager());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.add(milestone);
        Project updated = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant());
        save(updated);
        audit("milestone_added", project, milestone.id(), actor);
        return updated.milestones().get(milestones.size() - 1);
    }

    public Milestone updateMilestone(String projectId, String milestoneId, Milestone changes, User actor) {
        Project project = requireEditable(projectId, actor);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone changed = new Milestone(milestoneId, changes.name(), changes.description(), changes.startDate(),
                changes.endDate(), changes.progressTime(), changes.assignedEmployees(), changes.status(), null,
                changes.dependsOn());
        requireEmployees(changed, Set.copyOf(project.milestones().get(index).assignedEmployees()), project.manager());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, changed);
        Project updated = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant());
        save(updated);
        audit("milestone_updated", project, milestoneId, actor);
        return updated.milestones().get(index);
    }

    /**
     * Moves a milestone to a new status. Employees can only send their own milestones to AWAITING_REVIEW,
     * and cannot reopen one that is already COMPLETE; admins and the project's manager can set any status.
     * Dependencies apply to everyone: a milestone cannot move past TODO until what it depends on is complete.
     */
    public Milestone changeMilestoneStatus(String projectId, String milestoneId, CompletionStatus status,
                                           User actor) {
        if (status == null) {
            throw new IllegalArgumentException("status must be one of TODO, IN_PROGRESS, AWAITING_REVIEW, COMPLETE");
        }
        Project project = require(projectId, actor);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone milestone = project.milestones().get(index);
        if (actor.role() == Role.EMPLOYEE) {
            if (!milestone.assignedEmployees().contains(actor.id())) {
                throw new ForbiddenException("Access denied");
            }
            if (status != CompletionStatus.AWAITING_REVIEW) {
                throw new ForbiddenException("Employees can only set a milestone to AWAITING_REVIEW");
            }
            if (milestone.status() == CompletionStatus.COMPLETE) {
                throw new ConflictException("This milestone is already complete");
            }
        }
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, milestone.withStatus(status, null));
        Milestone changed = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant())
                .milestones().get(index);
        // Only the status and completion time are written, so progress logged meanwhile is not overwritten.
        if (!projects.setMilestoneStatus(projectId, milestoneId, status, changed.completedAt())) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Milestone " + milestoneId + " not found");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", milestone.status().name());
        details.put("to", status.name());
        audit("milestone_status_changed", project, milestoneId, actor, details);
        return changed;
    }

    /** Removes the milestone from the project; the repository keeps it as archived. */
    public void archiveMilestone(String projectId, String milestoneId, User actor) {
        Project project = requireEditable(projectId, actor);
        int index = requireMilestoneIndex(project, milestoneId);
        List<String> dependents = ProjectRules.dependentsOf(project, milestoneId).stream()
                .map(Milestone::name).toList();
        if (!dependents.isEmpty()) {
            throw new ConflictException("Other milestones depend on '" + project.milestones().get(index).name()
                    + "'; remove those dependencies first: " + String.join(", ", dependents));
        }
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.remove(index);
        save(project.withMilestones(milestones));
        audit("milestone_archived", project, milestoneId, actor);
    }

    static int requireMilestoneIndex(Project project, String milestoneId) {
        for (int index = 0; index < project.milestones().size(); index++) {
            if (project.milestones().get(index).id().equals(milestoneId)) {
                return index;
            }
        }
        throw new NotFoundException("Milestone " + milestoneId + " not found");
    }

    private static Set<String> assignedBefore(Project project, Milestone milestone) {
        return project.milestones().stream()
                .filter(existing -> existing.id().equals(milestone.id()))
                .findFirst()
                .map(existing -> Set.copyOf(existing.assignedEmployees()))
                .orElse(Set.of());
    }

    /**
     * Newly assigned employees must be active employees on the project manager's team; employees already assigned
     * are kept as they are, even if they have since moved to another team.
     */
    private void requireEmployees(Milestone milestone, Set<String> alreadyAssigned, String projectManagerId) {
        for (String employeeId : milestone.assignedEmployees()) {
            if (alreadyAssigned.contains(employeeId)) {
                continue;
            }
            User employee = requireActiveUser(employeeId, Role.EMPLOYEE, "assignedEmployees");
            if (!projectManagerId.equals(employee.managerId())) {
                throw new IllegalArgumentException("assignedEmployees must be on the project manager's team: "
                        + employeeId);
            }
        }
    }

    private User requireActiveUser(String userId, Role role, String field) {
        return users.findById(userId)
                .filter(user -> !user.archived() && user.role() == role)
                .orElseThrow(() -> new IllegalArgumentException(field + " must reference an active "
                        + role.name().toLowerCase() + " by user id: " + userId));
    }

    private void save(Project project) {
        if (!projects.replace(project)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Project " + project.id() + " not found");
        }
    }

    private void audit(String action, Project project, String milestoneId, User actor) {
        audit(action, project, milestoneId, actor, Map.of());
    }

    private void audit(String action, Project project, String milestoneId, User actor, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", project.id());
        details.put("projectName", project.name());
        if (milestoneId != null) {
            details.put("milestoneId", milestoneId);
        }
        details.putAll(extra);
        auditService.recordAction(action, actor.email(), details);
    }
}
