package com.acme.projects;

import com.acme.audit.AuditService;
import com.acme.errors.ConflictException;
import com.acme.errors.NotFoundException;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.repos.ProjectStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business rules for projects and their milestones; every change is recorded as an audit entry. */
public class ProjectService {
    private static final String ACTOR = "system";

    private final ProjectStore projects;
    private final AuditService auditService;

    public ProjectService(ProjectStore projects, AuditService auditService) {
        this.projects = projects;
        this.auditService = auditService;
    }

    public List<Project> findAll(boolean archived) {
        return projects.findAll(archived);
    }

    public Project require(String id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }

    public Project create(Project project) {
        // Archived projects keep their ids, so an id can never be reused.
        if (projects.exists(project.id())) {
            throw new ConflictException("A project with id " + project.id() + " already exists");
        }
        projects.insert(project);
        audit("project_created", project, null);
        return project;
    }

    /** Applies the changes to the project; its milestones are kept unless {@code replaceMilestones} is set. */
    public Project update(String id, Project changes, boolean replaceMilestones) {
        Project existing = require(id);
        Project updated = new Project(id, changes.name(), changes.startDate(), changes.endDate(),
                changes.progressTime(), changes.manager(), changes.budget(), changes.status(),
                replaceMilestones ? changes.milestones() : existing.milestones());
        save(updated);
        audit("project_updated", updated, null);
        return updated;
    }

    /** Hides the project from the active views; it stays stored and can be restored. */
    public void archive(String id) {
        Project project = require(id);
        if (!projects.archive(id)) {
            // Archived by another request between the lookup and the write.
            throw new NotFoundException("Project " + id + " not found");
        }
        audit("project_archived", project, null);
    }

    public Project restore(String id) {
        if (!projects.restore(id)) {
            throw new NotFoundException("Archived project " + id + " not found");
        }
        Project project = require(id);
        audit("project_restored", project, null);
        return project;
    }

    public Milestone addMilestone(String projectId, Milestone milestone) {
        Project project = require(projectId);
        if (project.milestones().stream().anyMatch(existing -> existing.id().equals(milestone.id()))) {
            throw new ConflictException("A milestone with id " + milestone.id() + " already exists");
        }
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.add(milestone);
        save(withMilestones(project, milestones));
        audit("milestone_added", project, milestone.id());
        return milestone;
    }

    public Milestone updateMilestone(String projectId, String milestoneId, Milestone changes) {
        Project project = require(projectId);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone updated = new Milestone(milestoneId, changes.name(), changes.startDate(), changes.endDate(),
                changes.progressTime(), changes.assignedEmployees(), changes.status());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, updated);
        save(withMilestones(project, milestones));
        audit("milestone_updated", project, milestoneId);
        return updated;
    }

    /** Removes the milestone from the project; the store keeps it as archived. */
    public void archiveMilestone(String projectId, String milestoneId) {
        Project project = require(projectId);
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.remove(requireMilestoneIndex(project, milestoneId));
        save(withMilestones(project, milestones));
        audit("milestone_archived", project, milestoneId);
    }

    private static int requireMilestoneIndex(Project project, String milestoneId) {
        for (int index = 0; index < project.milestones().size(); index++) {
            if (project.milestones().get(index).id().equals(milestoneId)) {
                return index;
            }
        }
        throw new NotFoundException("Milestone " + milestoneId + " not found");
    }

    private static Project withMilestones(Project project, List<Milestone> milestones) {
        return new Project(project.id(), project.name(), project.startDate(), project.endDate(),
                project.progressTime(), project.manager(), project.budget(), project.status(), milestones);
    }

    private void save(Project project) {
        if (!projects.replace(project)) {
            // Deleted by another request between the lookup and the write.
            throw new NotFoundException("Project " + project.id() + " not found");
        }
    }

    private void audit(String action, Project project, String milestoneId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", project.id());
        details.put("projectName", project.name());
        if (milestoneId != null) {
            details.put("milestoneId", milestoneId);
        }
        auditService.recordAction(action, ACTOR, details);
    }
}
