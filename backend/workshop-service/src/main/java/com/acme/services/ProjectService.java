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
import java.util.HashMap;
import java.util.HashSet;
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
 * Projects can depend on other projects: only admins set those dependencies, and a project cannot start, nor can
 * any of its milestones, until every project it depends on is complete.
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
        checkProjectDependencies(null, created);
        projects.insert(created);
        audit("project_created", created, null, actor);
        return view(created, actor);
    }

    /**
     * Applies the changes to the project. Its milestones are kept unless {@code replaceMilestones} is set, and its
     * project dependencies unless {@code replaceDependencies} is set.
     */
    public ProjectView update(String id, Project changes, boolean replaceMilestones, boolean replaceDependencies,
                              User actor) {
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
        List<String> dependsOn = replaceDependencies ? changes.dependsOn() : existing.dependsOn();
        if (actor.role() != Role.ADMIN && !dependsOn.equals(existing.dependsOn())) {
            throw new ForbiddenException("Only admins can change a project's dependencies");
        }
        if (replaceMilestones) {
            // Checked against the manager the project has after this update.
            changes.milestones().forEach(milestone ->
                    requireEmployees(milestone, assignedBefore(existing, milestone), changes.manager()));
        }
        Project updated = ProjectRules.apply(existing, new Project(id, changes.name(), description,
                changes.startDate(), changes.endDate(), changes.progressTime(), changes.manager(), changes.budget(),
                changes.status(), replaceMilestones ? changes.milestones() : existing.milestones(), null, dependsOn),
                clock.instant());
        checkProjectDependencies(existing, updated);
        save(updated);
        audit("project_updated", updated, null, actor);
        return view(updated, actor);
    }

    /** Hides the project from the active views; it stays stored and can be restored. */
    public void archive(String id, User actor) {
        Project project = requireEditable(id, actor);
        List<String> dependents = dependentsOf(id).stream().map(Project::name).toList();
        if (!dependents.isEmpty()) {
            throw new ConflictException("Other projects depend on '" + project.name()
                    + "'; remove those dependencies first: " + String.join(", ", dependents));
        }
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
        checkProjectDependencies(project, updated);
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
        checkProjectDependencies(project, updated);
        save(updated);
        audit("milestone_updated", project, milestoneId, actor);
        return updated.milestones().get(index);
    }

    /**
     * Moves a milestone to a new status. Employees can only send their own milestones to AWAITING_REVIEW,
     * and cannot reopen one that is already COMPLETE; admins and the project's manager can set any status.
     * Dependencies apply to everyone: a milestone cannot move past todo until what it depends on is complete.
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
        Project updated = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant());
        checkProjectDependencies(project, updated);
        Milestone changed = updated.milestones().get(index);
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

    /**
     * The rules for dependencies between projects, checked against what changed from {@code before} (null for a
     * new project) to {@code after}:
     * <ul>
     *   <li>dependencies point at other active projects and never form a cycle;</li>
     *   <li>the project cannot start or complete, and none of its milestones can start, until every project it
     *       depends on is complete;</li>
     *   <li>a completed project cannot be reopened while a project depending on it has started.</li>
     * </ul>
     */
    private void checkProjectDependencies(Project before, Project after) {
        boolean dependenciesChanged = before == null || !before.dependsOn().equals(after.dependsOn());
        if (dependenciesChanged) {
            for (String dependency : after.dependsOn()) {
                if (dependency.equals(after.id())) {
                    throw new IllegalArgumentException("Project '" + after.name() + "' cannot depend on itself");
                }
                if (projects.findById(dependency).isEmpty()) {
                    throw new IllegalArgumentException("Project '" + after.name()
                            + "' depends on a project that does not exist or is archived: " + dependency);
                }
            }
            requireNoProjectCycle(after);
        }

        List<String> incomplete = after.dependsOn().stream()
                .map(dependency -> projects.findById(dependency))
                .filter(found -> found.isEmpty() || found.get().status() != CompletionStatus.COMPLETE)
                .map(found -> found.map(Project::name).orElse("(archived project)"))
                .toList();
        if (!incomplete.isEmpty()) {
            boolean projectStarting = after.started()
                    && (before == null || before.status() != after.status() || dependenciesChanged);
            if (projectStarting) {
                throw new ConflictException("Project '" + after.name() + "' cannot start or complete until the"
                        + " projects it depends on are complete: " + String.join(", ", incomplete));
            }
            boolean milestoneStarting = after.milestones().stream().anyMatch(milestone -> milestone.started()
                    && (before == null || before.milestones().stream().noneMatch(previous ->
                            previous.id().equals(milestone.id()) && previous.status() == milestone.status())));
            if (milestoneStarting) {
                throw new ConflictException("Milestones of project '" + after.name() + "' cannot start until the"
                        + " projects it depends on are complete: " + String.join(", ", incomplete));
            }
        }

        boolean reopened = before != null && before.status() == CompletionStatus.COMPLETE
                && after.status() != CompletionStatus.COMPLETE;
        if (reopened) {
            List<String> startedDependents = dependentsOf(after.id()).stream()
                    .filter(Project::started).map(Project::name).toList();
            if (!startedDependents.isEmpty()) {
                throw new ConflictException("Project '" + after.name() + "' cannot be reopened while projects that"
                        + " depend on it have started: " + String.join(", ", startedDependents));
            }
        }
    }

    /** Follows dependencies from the changed project through every active project, looking for a way back. */
    private void requireNoProjectCycle(Project changed) {
        Map<String, Project> byId = new HashMap<>();
        projects.findAll(false).forEach(project -> byId.put(project.id(), project));
        byId.put(changed.id(), changed);
        List<String> path = new ArrayList<>(List.of(changed.id()));
        if (reachesBack(changed.id(), changed.dependsOn(), byId, path, new HashSet<>())) {
            throw new IllegalArgumentException("Project dependencies cannot form a cycle: "
                    + String.join(" → ", path.stream().map(id -> byId.get(id).name()).toList()));
        }
    }

    private static boolean reachesBack(String start, List<String> next, Map<String, Project> byId, List<String> path,
                                       Set<String> visited) {
        for (String id : next) {
            path.add(id);
            if (id.equals(start)) {
                return true;
            }
            Project project = byId.get(id);
            if (project != null && visited.add(id) && reachesBack(start, project.dependsOn(), byId, path, visited)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    /** Active projects that list this one in their dependsOn. */
    private List<Project> dependentsOf(String projectId) {
        return projects.findAll(false).stream()
                .filter(project -> project.dependsOn().contains(projectId))
                .toList();
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
