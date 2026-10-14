package com.acme.repos;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where projects are kept; milestones are stored as part of their project.
 * Nothing is ever deleted: projects and milestones are archived instead and stay in the repository.
 */
public interface ProjectRepository {
    /** Fails when a project with the same id already exists, archived or not. */
    void insert(Project project);

    /** True when a project with this id exists, archived or not. */
    boolean exists(String id);

    /** The active project with its active milestones; empty when it does not exist or is archived. */
    Optional<Project> findById(String id);

    /** Active or archived projects, ordered by start date, then name, each with its active milestones. */
    List<Project> findAll(boolean archived);

    /**
     * Replaces an active project's fields and milestones. Milestones missing from the new list are archived,
     * not deleted. Returns false when there is no active project with this id.
     */
    boolean replace(Project project);

    /**
     * Sets one active milestone's status and completion time without touching its other fields, so it cannot
     * overwrite progress logged at the same moment. Returns false when there is no such active milestone on an
     * active project.
     */
    boolean setMilestoneStatus(String projectId, String milestoneId, CompletionStatus status, Instant completedAt);

    /** The archived milestones of a project, whether the project itself is archived or not, in their last order. */
    List<Milestone> findArchivedMilestones(String projectId);

    /** Returns false when there is no active project with this id. */
    boolean archive(String id);

    /** Makes an archived project active again; returns false when there is no archived project with this id. */
    boolean restore(String id);
}
