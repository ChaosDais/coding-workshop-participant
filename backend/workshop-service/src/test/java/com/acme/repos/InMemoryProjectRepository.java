package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Test double for the PostgreSQL repository, with the same ordering, duplicate and archiving behaviour for
 * projects.
 */
public class InMemoryProjectRepository implements ProjectRepository {
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Set<String> archived = ConcurrentHashMap.newKeySet();
    /** Milestones dropped from a project by replace, which the PostgreSQL repository keeps as archived. */
    private final Map<String, List<Milestone>> archivedMilestones = new ConcurrentHashMap<>();

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
    public synchronized boolean replace(Project project) {
        Project previous = projects.get(project.id());
        if (previous == null || archived.contains(project.id())) {
            return false;
        }
        Set<String> kept = new HashSet<>(project.milestones().stream().map(Milestone::id).toList());
        List<Milestone> dropped = previous.milestones().stream().filter(milestone -> !kept.contains(milestone.id()))
                .toList();
        List<Milestone> archivedList = new ArrayList<>(archivedMilestones.getOrDefault(project.id(), List.of()));
        archivedList.removeIf(milestone -> kept.contains(milestone.id()));
        archivedList.addAll(dropped);
        archivedMilestones.put(project.id(), archivedList);
        projects.put(project.id(), project);
        return true;
    }

    @Override
    public synchronized boolean setAssignees(String projectId, Map<String, List<String>> assigneesByMilestone) {
        Project project = projects.get(projectId);
        if (project == null || archived.contains(projectId) || !assigneesByMilestone.keySet().stream()
                .allMatch(id -> project.milestones().stream().anyMatch(milestone -> milestone.id().equals(id)))) {
            return false;
        }
        assigneesByMilestone.forEach((milestoneId, assignees) -> updateMilestone(projectId, milestoneId,
                milestone -> new Milestone(milestone.id(), milestone.name(), milestone.description(),
                        milestone.startDate(), milestone.endDate(), milestone.progressTime(), assignees,
                        milestone.status(), milestone.completedAt(), milestone.dependsOn(),
                        milestone.expectedHours(), milestone.statusChangedAt())));
        return true;
    }

    @Override
    public List<Milestone> findArchivedMilestones(String projectId) {
        return List.copyOf(archivedMilestones.getOrDefault(projectId, List.of()));
    }

    @Override
    public synchronized boolean setMilestoneStatus(String projectId, String milestoneId, CompletionStatus status,
                                                   Instant completedAt, Instant statusChangedAt) {
        return updateMilestone(projectId, milestoneId,
                milestone -> milestone.withStatus(status, completedAt).withStatusChangedAt(statusChangedAt));
    }

    /** Used by the in-memory time entry repository, which adds logged hours to a milestone's progress. */
    synchronized boolean updateMilestone(String projectId, String milestoneId, UnaryOperator<Milestone> change) {
        Project project = projects.get(projectId);
        if (project == null || archived.contains(projectId)
                || project.milestones().stream().noneMatch(milestone -> milestone.id().equals(milestoneId))) {
            return false;
        }
        List<Milestone> milestones = project.milestones().stream()
                .map(milestone -> milestone.id().equals(milestoneId) ? change.apply(milestone) : milestone)
                .toList();
        projects.put(projectId, project.withMilestones(milestones));
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
