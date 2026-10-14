package com.acme.projects;

import com.acme.audit.AuditService;
import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.models.Role;
import com.acme.models.User;
import com.acme.repos.ProjectStore;
import com.acme.repos.UserStore;

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
 * </ul>
 * Whoever makes the change, only employees on the project manager's team can be newly assigned to its milestones;
 * an employee moves to another team by an admin changing their manager.
 * A project's manager must be an active manager and assigned employees must be active employees; references are
 * checked when they change, so existing ones stay valid if a user is later archived or moved to another team.
 */
public class ProjectService {
    private final ProjectStore projects;
    private final UserStore users;
    private final AuditService auditService;

    public ProjectService(ProjectStore projects, UserStore users, AuditService auditService) {
        this.projects = projects;
        this.users = users;
        this.auditService = auditService;
    }

    /** Admins see every project; managers see the active projects they manage. */
    public List<Project> findAll(boolean archived, User actor) {
        if (actor.role() == Role.ADMIN) {
            return projects.findAll(archived);
        }
        if (archived) {
            throw new ForbiddenException("Access denied");
        }
        return projects.findAll(false).stream().filter(project -> actor.id().equals(project.manager())).toList();
    }

    public Project require(String id, User actor) {
        Project project = projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
        if (actor.role() != Role.ADMIN && !actor.id().equals(project.manager())) {
            throw new ForbiddenException("Access denied");
        }
        return project;
    }

    public Project create(Project project, User actor) {
        // Archived projects keep their ids, so an id can never be reused.
        if (projects.exists(project.id())) {
            throw new ConflictException("A project with id " + project.id() + " already exists");
        }
        requireActiveUser(project.manager(), Role.MANAGER, "manager");
        project.milestones().forEach(milestone -> requireEmployees(milestone, Set.of(), project.manager()));
        projects.insert(project);
        audit("project_created", project, null, actor);
        return project;
    }

    /** Applies the changes to the project; its milestones are kept unless {@code replaceMilestones} is set. */
    public Project update(String id, Project changes, boolean replaceMilestones, User actor) {
        Project existing = require(id, actor);
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
        return updated;
    }

    /** Hides the project from the active views; it stays stored and can be restored. */
    public void archive(String id, User actor) {
        Project project = require(id, actor);
        if (!projects.archive(id)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Project " + id + " not found");
        }
        audit("project_archived", project, null, actor);
    }

    public Project restore(String id, User actor) {
        if (!projects.restore(id)) {
            throw new NotFoundException("Archived project " + id + " not found");
        }
        Project project = require(id, actor);
        audit("project_restored", project, null, actor);
        return project;
    }

    public Milestone addMilestone(String projectId, Milestone milestone, User actor) {
        Project project = require(projectId, actor);
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
        Project project = require(projectId, actor);
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
    public void archiveMilestone(String projectId, String milestoneId, User actor) {
        Project project = require(projectId, actor);
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.remove(requireMilestoneIndex(project, milestoneId));
        save(withMilestones(project, milestones));
        audit("milestone_archived", project, milestoneId, actor);
    }

    private static int requireMilestoneIndex(Project project, String milestoneId) {
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
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", project.id());
        details.put("projectName", project.name());
        if (milestoneId != null) {
            details.put("milestoneId", milestoneId);
        }
        auditService.recordAction(action, actor.email(), details);
    }
}
