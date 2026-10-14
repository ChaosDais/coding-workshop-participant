package com.acme.projects;

import com.acme.audit.AuditService;
import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.ProjectStore;
import com.acme.repos.TimeEntryStore;
import com.acme.repos.UserStore;

import java.math.BigDecimal;
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
 *   <li>Employees can see only the active projects where they are assigned to a milestone, and only those
 *       milestones; the one change they can make is moving such a milestone to AWAITING_REVIEW.</li>
 * </ul>
 * Whoever makes the change, only employees on the project manager's team can be newly assigned to its milestones;
 * an employee moves to another team by an admin changing their manager.
 * A project's manager must be an active manager and assigned employees must be active employees; references are
 * checked when they change, so existing ones stay valid if a user is later archived or moved to another team.
 */
public class ProjectService {
    private final ProjectStore projects;
    private final UserStore users;
    private final TimeEntryStore timeEntries;
    private final AuditService auditService;

    public ProjectService(ProjectStore projects, UserStore users, TimeEntryStore timeEntries,
                          AuditService auditService) {
        this.projects = projects;
        this.users = users;
        this.timeEntries = timeEntries;
        this.auditService = auditService;
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
        Project project = projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
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
                ? ProjectView.forEmployee(project, actor.id())
                : ProjectView.full(project, spent.getOrDefault(project.id(), BigDecimal.ZERO));
    }

    public ProjectView create(Project project, User actor) {
        // Archived projects keep their ids, so an id can never be reused.
        if (projects.exists(project.id())) {
            throw new ConflictException("A project with id " + project.id() + " already exists");
        }
        requireActiveUser(project.manager(), Role.MANAGER, "manager");
        project.milestones().forEach(milestone -> requireEmployees(milestone, Set.of(), project.manager()));
        projects.insert(project);
        audit("project_created", project, null, actor);
        return view(project, actor);
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
        Project updated = new Project(id, changes.name(), description, changes.startDate(), changes.endDate(),
                changes.progressTime(), changes.manager(), changes.budget(), changes.status(),
                replaceMilestones ? changes.milestones() : existing.milestones());
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
        save(withMilestones(project, milestones));
        audit("milestone_added", project, milestone.id(), actor);
        return milestone;
    }

    public Milestone updateMilestone(String projectId, String milestoneId, Milestone changes, User actor) {
        Project project = requireEditable(projectId, actor);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone updated = new Milestone(milestoneId, changes.name(), changes.description(), changes.startDate(),
                changes.endDate(), changes.progressTime(), changes.assignedEmployees(), changes.status());
        requireEmployees(updated, Set.copyOf(project.milestones().get(index).assignedEmployees()), project.manager());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, updated);
        save(withMilestones(project, milestones));
        audit("milestone_updated", project, milestoneId, actor);
        return updated;
    }

    /** Removes the milestone from the project; the store keeps it as archived. */
    /**
     * Moves a milestone to a new status. Employees can only send their own milestones to AWAITING_REVIEW,
     * and cannot reopen one that is already COMPLETE; admins and the project's manager can set any status.
     */
    public Milestone changeMilestoneStatus(String projectId, String milestoneId, CompletionStatus status,
                                           User actor) {
        if (status == null) {
            throw new IllegalArgumentException("status must be one of TODO, IN_PROGRESS, AWAITING_REVIEW, COMPLETE");
        }
        Project project = require(projectId, actor);
        Milestone milestone = project.milestones().get(requireMilestoneIndex(project, milestoneId));
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
        if (!projects.setMilestoneStatus(projectId, milestoneId, status)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Milestone " + milestoneId + " not found");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", milestone.status().name());
        details.put("to", status.name());
        audit("milestone_status_changed", project, milestoneId, actor, details);
        return new Milestone(milestone.id(), milestone.name(), milestone.description(), milestone.startDate(),
                milestone.endDate(), milestone.progressTime(), milestone.assignedEmployees(), status);
    }

    public void archiveMilestone(String projectId, String milestoneId, User actor) {
        Project project = requireEditable(projectId, actor);
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.remove(requireMilestoneIndex(project, milestoneId));
        save(withMilestones(project, milestones));
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

    private static Project withMilestones(Project project, List<Milestone> milestones) {
        return new Project(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(),
                project.progressTime(), project.manager(), project.budget(), project.status(), milestones);
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
