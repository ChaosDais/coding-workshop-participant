package com.acme.repos;

import com.acme.models.Project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps projects in the memory of one Lambda instance; they are lost when that instance is recycled. */
public class InMemoryProjectStore implements ProjectStore {
    private final Map<String, Project> projects = new ConcurrentHashMap<>();

    @Override
    public void insert(Project project) {
        if (projects.putIfAbsent(project.id(), project) != null) {
            throw new IllegalStateException("Project already exists: " + project.id());
        }
    }

    @Override
    public Optional<Project> findById(String id) {
        return Optional.ofNullable(projects.get(id));
    }

    @Override
    public List<Project> findAll() {
        return projects.values().stream()
                .sorted(Comparator.comparing(Project::startDate).thenComparing(Project::name))
                .toList();
    }

    @Override
    public boolean replace(Project project) {
        return projects.replace(project.id(), project) != null;
    }

    @Override
    public boolean deleteById(String id) {
        return projects.remove(id) != null;
    }
}
