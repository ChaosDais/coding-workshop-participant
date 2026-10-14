package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** Test double for the PostgreSQL store, with the same ordering, duplicate and archiving behaviour for projects. */
public class InMemoryProjectStore implements ProjectStore {
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Set<String> archived = ConcurrentHashMap.newKeySet();

    @Override
    public void insert(Project project) {
        if (projects.putIfAbsent(project.id(), project) != null) {
            throw new StorageException("Project already exists: " + project.id(), null);
        }
    }

    @Override
    public boolean exists(String id) {
        return projects.containsKey(id);
    }

    @Override
    public Optional<Project> findById(String id) {
        return archived.contains(id) ? Optional.empty() : Optional.ofNullable(projects.get(id));
    }

    @Override
    public List<Project> findAll(boolean archivedOnly) {
        return projects.values().stream()
                .filter(project -> archived.contains(project.id()) == archivedOnly)
                .sorted(Comparator.comparing(Project::startDate).thenComparing(Project::name))
                .toList();
    }

    @Override
    public boolean replace(Project project) {
        return !archived.contains(project.id()) && projects.replace(project.id(), project) != null;
    }

    @Override
    public synchronized boolean setMilestoneStatus(String projectId, String milestoneId, CompletionStatus status) {
        return updateMilestone(projectId, milestoneId, milestone -> new Milestone(milestone.id(), milestone.name(),
                milestone.description(), milestone.startDate(), milestone.endDate(), milestone.progressTime(),
                milestone.assignedEmployees(), status));
    }

    /** Used by the in-memory time store, which adds logged hours to a milestone's progress. */
    synchronized boolean updateMilestone(String projectId, String milestoneId, UnaryOperator<Milestone> change) {
        Project project = projects.get(projectId);
        if (project == null || archived.contains(projectId)
                || project.milestones().stream().noneMatch(milestone -> milestone.id().equals(milestoneId))) {
            return false;
        }
        List<Milestone> milestones = project.milestones().stream()
                .map(milestone -> milestone.id().equals(milestoneId) ? change.apply(milestone) : milestone)
                .toList();
        projects.put(projectId, new Project(project.id(), project.name(), project.description(), project.startDate(),
                project.endDate(), project.progressTime(), project.manager(), project.budget(), project.status(),
                milestones));
        return true;
    }

    @Override
    public boolean archive(String id) {
        return projects.containsKey(id) && archived.add(id);
    }

    @Override
    public boolean restore(String id) {
        return archived.remove(id);
    }
}
