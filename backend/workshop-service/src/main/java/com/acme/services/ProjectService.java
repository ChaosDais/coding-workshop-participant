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
import java.util.Optional;
import java.util.Set;

/**
 * Business rules for projects and their milestones; every change is recorded as an audit entry with the user
 * who made it.
 * <ul>
 *   <li>Admins can see and change every project, including archived ones.</li>
 *   <li>Managers can see and change only the active projects they manage, but cannot hand a project to another
 *       manager or change its description.</li>
 *   <li>Employees can see only the active, unfinished projects where they are assigned to a milestone, with all of that
 *       project's milestones; the one change they can make is moving one of their own milestones to
 *       AWAITING_REVIEW.</li>
 * </ul>
 * Whoever makes the change, only employees on the project manager's team can be newly assigned to its milestones;
 * an employee moves to another team by an admin changing their manager.
 * A project's manager must be an active manager and assigned employees must be active employees; references are
 * checked when they change, so existing ones stay valid if a user is later archived or moved to another team.
 * Dates, completion times and milestone dependencies follow {@link ProjectRules} on every change.
 * Projects can depend on other projects: only admins set those dependencies, and a project cannot start, nor can
 * any of its milestones, until every project it depends on is complete. An employee works on at most one unfinished
 * milestone per project.
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
        Map<String, ProjectView.Dependency> summaries = dependencySummaries();
        return visible.stream().map(project -> view(project, actor, spent, summaries)).toList();
    }

    public ProjectView get(String id, User actor) {
        return view(require(id, actor), actor);
    }

    /**
     * A project's active or archived milestones. Admins can list them for archived projects too; managers only for
     * the active projects they manage.
     */
    public List<Milestone> milestones(String projectId, boolean archived, User actor) {
        if (actor.role() == Role.ADMIN && !projects.exists(projectId)) {
            throw new NotFoundException("This project was not found; it may have been archived");
        }
        if (actor.role() == Role.ADMIN && archived) {
            return projects.findArchivedMilestones(projectId);
        }
        Project project = actor.role() == Role.ADMIN
                ? projects.findById(projectId).or(() -> projects.findAll(true).stream()
                        .filter(found -> found.id().equals(projectId)).findFirst()).orElseThrow()
                : requireEditable(projectId, actor);
        return archived ? projects.findArchivedMilestones(projectId) : project.milestones();
    }

    /** The active project, if this user may see it. */
    Project require(String id, User actor) {
        Project project = projects.findById(id)
                .orElseThrow(() -> new NotFoundException("This project was not found; it may have been archived"));
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
            // Employees see only the projects they currently work on: not completed ones.
            case EMPLOYEE -> project.status() != CompletionStatus.COMPLETE && project.milestones().stream()
                    .anyMatch(milestone -> milestone.assignedEmployees().contains(actor.id()));
        };
    }

    ProjectView view(Project project, User actor) {
        return view(project, actor, timeEntries.totalCostByProject(), dependencySummaries());
    }

    private static ProjectView view(Project project, User actor, Map<String, BigDecimal> spent,
                                    Map<String, ProjectView.Dependency> summaries) {
        List<ProjectView.Dependency> dependencies = project.dependsOn().stream()
                .map(id -> summaries.getOrDefault(id, new ProjectView.Dependency(id, null, null, true)))
                .toList();
        return actor.role() == Role.EMPLOYEE
                ? ProjectView.forEmployee(project, dependencies)
                : ProjectView.full(project, spent.getOrDefault(project.id(), BigDecimal.ZERO), dependencies);
    }

    /** Every project, active or archived, as the short summary shown for a dependency. */
    private Map<String, ProjectView.Dependency> dependencySummaries() {
        Map<String, ProjectView.Dependency> summaries = new HashMap<>();
        projects.findAll(true).forEach(project -> summaries.put(project.id(),
                new ProjectView.Dependency(project.id(), project.name(), project.status(), true)));
        projects.findAll(false).forEach(project -> summaries.put(project.id(),
                new ProjectView.Dependency(project.id(), project.name(), project.status(), false)));
        return summaries;
    }

    public ProjectView create(Project project, User actor) {
        // Archived projects keep their ids, so an id can never be reused.
        if (projects.exists(project.id())) {
            throw new ConflictException("A project with this id already exists");
        }
        requireActiveUser(project.manager(), Role.MANAGER, "manager");
        project.milestones().forEach(milestone -> requireEmployees(milestone, Set.of(), project.manager()));
        Project created = ProjectRules.apply(null, project, clock.instant());
        checkProjectDependencies(null, created);
        requireOneOpenMilestonePerEmployee(null, created);
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
                changes.status(), replaceMilestones ? changes.milestones() : existing.milestones(), null, dependsOn,
                changes.expectedHours(), null),
                clock.instant());
        checkProjectDependencies(existing, updated);
        requireOneOpenMilestonePerEmployee(existing, updated);
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
            throw new NotFoundException("This project was not found; it may have been archived");
        }
        audit("project_archived", project, null, actor);
    }

    public ProjectView restore(String id, User actor) {
        if (!projects.restore(id)) {
            throw new NotFoundException("This archived project was not found");
        }
        Project project = require(id, actor);
        audit("project_restored", project, null, actor);
        return view(project, actor);
    }

    public Milestone addMilestone(String projectId, Milestone milestone, User actor) {
        Project project = requireEditable(projectId, actor);
        if (project.milestones().stream().anyMatch(existing -> existing.id().equals(milestone.id()))) {
            throw new ConflictException("A milestone with this id already exists");
        }
        requireEmployees(milestone, Set.of(), project.manager());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.add(milestone);
        Project updated = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant());
        checkProjectDependencies(project, updated);
        requireOneOpenMilestonePerEmployee(project, updated);
        save(updated);
        audit("milestone_added", project, milestone, actor);
        return updated.milestones().get(milestones.size() - 1);
    }

    public Milestone updateMilestone(String projectId, String milestoneId, Milestone changes, User actor) {
        Project project = requireEditable(projectId, actor);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone changed = new Milestone(milestoneId, changes.name(), changes.description(), changes.startDate(),
                changes.endDate(), changes.progressTime(), changes.assignedEmployees(), changes.status(), null,
                changes.dependsOn(), changes.expectedHours(), null);
        requireEmployees(changed, Set.copyOf(project.milestones().get(index).assignedEmployees()), project.manager());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, changed);
        Project updated = ProjectRules.apply(project, project.withMilestones(milestones), clock.instant());
        checkProjectDependencies(project, updated);
        requireOneOpenMilestonePerEmployee(project, updated);
        save(updated);
        audit("milestone_updated", project, changed, actor);
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
        requireOneOpenMilestonePerEmployee(project, updated);
        Milestone changed = updated.milestones().get(index);
        // Only the status and completion time are written, so progress logged meanwhile is not overwritten.
        if (!projects.setMilestoneStatus(projectId, milestoneId, status, changed.completedAt(),
                changed.statusChangedAt())) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("This milestone was not found; it may have been archived");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", milestone.status().name());
        details.put("to", status.name());
        audit("milestone_status_changed", project, milestone, actor, details);
        return changed;
    }

    /** Adds an employee from the project manager's team to a milestone. */
    public Milestone assign(String projectId, String milestoneId, String employeeId, User actor) {
        Project project = requireEditable(projectId, actor);
        Milestone milestone = project.milestones().get(requireMilestoneIndex(project, milestoneId));
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employeeId is required");
        }
        if (milestone.assignedEmployees().contains(employeeId)) {
            throw new ConflictException(employeeName(employeeId) + " is already assigned to " + milestone.name());
        }
        List<String> assignees = new ArrayList<>(milestone.assignedEmployees());
        assignees.add(employeeId);
        requireEmployees(withAssignees(milestone, assignees), Set.copyOf(milestone.assignedEmployees()),
                project.manager());
        requireOneOpenMilestonePerEmployee(project, replaceAssignees(project, Map.of(milestoneId, assignees)));
        setAssignees(project, Map.of(milestoneId, assignees));
        auditAssignment("employee_assigned", project, milestone, employeeId, actor, Map.of());
        return withAssignees(milestone, assignees);
    }

    /** Takes an employee off a milestone. */
    public void unassign(String projectId, String milestoneId, String employeeId, User actor) {
        Project project = requireEditable(projectId, actor);
        Milestone milestone = project.milestones().get(requireMilestoneIndex(project, milestoneId));
        if (!milestone.assignedEmployees().contains(employeeId)) {
            throw new NotFoundException(employeeName(employeeId) + " is not assigned to " + milestone.name());
        }
        List<String> assignees = new ArrayList<>(milestone.assignedEmployees());
        assignees.remove(employeeId);
        setAssignees(project, Map.of(milestoneId, assignees));
        auditAssignment("employee_unassigned", project, milestone, employeeId, actor, Map.of());
    }

    /**
     * Moves an employee from one milestone of the project to another, in one step, so they are never left on both
     * or on neither. They must be on the project manager's team to join the new milestone.
     */
    public Milestone move(String projectId, String fromMilestoneId, String employeeId, String toMilestoneId,
                          User actor) {
        Project project = requireEditable(projectId, actor);
        Milestone from = project.milestones().get(requireMilestoneIndex(project, fromMilestoneId));
        if (toMilestoneId == null || toMilestoneId.isBlank()) {
            throw new IllegalArgumentException("toMilestoneId is required");
        }
        Milestone to = project.milestones().get(requireMilestoneIndex(project, toMilestoneId));
        if (from.id().equals(to.id())) {
            throw new IllegalArgumentException("Choose a different milestone to move to");
        }
        if (!from.assignedEmployees().contains(employeeId)) {
            throw new NotFoundException(employeeName(employeeId) + " is not assigned to " + from.name());
        }
        if (to.assignedEmployees().contains(employeeId)) {
            throw new ConflictException(employeeName(employeeId) + " is already assigned to " + to.name());
        }
        List<String> leaving = new ArrayList<>(from.assignedEmployees());
        leaving.remove(employeeId);
        List<String> joining = new ArrayList<>(to.assignedEmployees());
        joining.add(employeeId);
        requireEmployees(withAssignees(to, joining), Set.copyOf(to.assignedEmployees()), project.manager());
        requireOneOpenMilestonePerEmployee(project,
                replaceAssignees(project, Map.of(from.id(), leaving, to.id(), joining)));
        setAssignees(project, Map.of(from.id(), leaving, to.id(), joining));
        auditAssignment("employee_reassigned", project, to, employeeId, actor,
                Map.of("fromMilestoneId", from.id(), "fromMilestoneName", from.name()));
        return withAssignees(to, joining);
    }

    /**
     * An employee works on at most one unfinished milestone of a project at a time (they can still be on several
     * projects). Checked for people newly put to work on an unfinished milestone, which includes reopening a
     * completed one, so data saved before the rule stays editable.
     */
    private void requireOneOpenMilestonePerEmployee(Project before, Project after) {
        for (Milestone milestone : after.milestones()) {
            if (milestone.status() == CompletionStatus.COMPLETE) {
                continue;
            }
            Set<String> alreadyWorking = before == null ? Set.of() : before.milestones().stream()
                    .filter(previous -> previous.id().equals(milestone.id())
                            && previous.status() != CompletionStatus.COMPLETE)
                    .findFirst()
                    .map(previous -> Set.copyOf(previous.assignedEmployees()))
                    .orElse(Set.of());
            for (String employeeId : milestone.assignedEmployees()) {
                if (alreadyWorking.contains(employeeId)) {
                    continue;
                }
                after.milestones().stream()
                        .filter(other -> !other.id().equals(milestone.id())
                                && other.status() != CompletionStatus.COMPLETE
                                && other.assignedEmployees().contains(employeeId))
                        .findFirst()
                        .ifPresent(other -> {
                            throw new ConflictException(employeeName(employeeId) + " is already working on '"
                                    + other.name() + "' in this project; an employee works on one unfinished"
                                    + " milestone per project, so move them instead");
                        });
            }
        }
    }

    private static Project replaceAssignees(Project project, Map<String, List<String>> assigneesByMilestone) {
        return project.withMilestones(project.milestones().stream()
                .map(milestone -> assigneesByMilestone.containsKey(milestone.id())
                        ? withAssignees(milestone, assigneesByMilestone.get(milestone.id())) : milestone)
                .toList());
    }

    private void setAssignees(Project project, Map<String, List<String>> assigneesByMilestone) {
        if (!projects.setAssignees(project.id(), assigneesByMilestone)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Milestone not found");
        }
    }

    private static Milestone withAssignees(Milestone milestone, List<String> assignees) {
        return new Milestone(milestone.id(), milestone.name(), milestone.description(), milestone.startDate(),
                milestone.endDate(), milestone.progressTime(), assignees, milestone.status(), milestone.completedAt(),
                milestone.dependsOn(), milestone.expectedHours(), milestone.statusChangedAt());
    }

    private String employeeName(String employeeId) {
        return users.findById(employeeId).map(User::name).orElse("An unknown employee");
    }

    private void auditAssignment(String action, Project project, Milestone milestone, String employeeId, User actor,
                                 Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>(extra);
        details.put("employeeId", employeeId);
        details.put("employeeName", employeeName(employeeId));
        audit(action, project, milestone, actor, details);
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
        audit("milestone_archived", project, project.milestones().get(index), actor);
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
                            + "' depends on a project that does not exist or is archived");
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
        throw new NotFoundException("This milestone was not found; it may have been archived");
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
                throw new IllegalArgumentException(employee.name()
                        + " is not on the project manager's team, so cannot be assigned to this project");
            }
        }
    }

    private User requireActiveUser(String userId, Role role, String field) {
        Optional<User> found = users.findById(userId);
        return found.filter(user -> !user.archived() && user.role() == role)
                .orElseThrow(() -> new IllegalArgumentException(found
                        .map(user -> user.name() + " is not an active " + role.name().toLowerCase())
                        .orElse(field + " must be an active " + role.name().toLowerCase())));
    }

    private void save(Project project) {
        if (!projects.replace(project)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("This project was not found; it may have been archived");
        }
    }

    /** Records a change to the project, or to one of its milestones when {@code milestone} is set. */
    private void audit(String action, Project project, Milestone milestone, User actor) {
        audit(action, project, milestone, actor, Map.of());
    }

    private void audit(String action, Project project, Milestone milestone, User actor, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", project.id());
        details.put("projectName", project.name());
        if (milestone != null) {
            // The name is stored too, so the log stays readable after the milestone is renamed or archived.
            details.put("milestoneId", milestone.id());
            details.put("milestoneName", milestone.name());
        }
        details.putAll(extra);
        auditService.recordAction(action, actor, details);
    }
}
