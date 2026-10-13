package com.acme.repos;

import com.acme.models.Project;

import java.util.List;
import java.util.Optional;

/** Where projects are kept; milestones are stored as part of their project. */
public interface ProjectStore {
    /** Fails when a project with the same id already exists. */
    void insert(Project project);

    Optional<Project> findById(String id);

    /** Ordered by start date, then name. */
    List<Project> findAll();

    /** Replaces the stored project including its milestones; returns false when the id does not exist. */
    boolean replace(Project project);

    boolean deleteById(String id);
}
