package com.acme.services;

import com.acme.errors.ConflictException;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The integrity rules every change to a project and its milestones must keep. Each rule is checked against what
 * changed between {@code before} (null for a new project) and {@code after}, so data saved before a rule existed
 * stays readable and editable as long as the edit does not touch what the rule is about.
 * <ul>
 *   <li>Completion times: set when something becomes COMPLETE, kept while it stays complete, cleared on reopening.
 *       Status-change times: set on creation and on every status change, kept otherwise.</li>
 *   <li>Frozen dates: while a project stays complete, its dates and its milestones' dates cannot change, and
 *       neither can the dates of a milestone that stays complete. A change that reopens it may also move them.</li>
 *   <li>Milestone dates fall within the project's dates.</li>
 *   <li>Dependencies point at other milestones of the same project and never form a cycle.</li>
 *   <li>A milestone cannot start (move past todo) or complete until everything it depends on is complete, and a
 *       completed milestone cannot be reopened while something depending on it has started.</li>
 * </ul>
 */
final class ProjectRules {
    private ProjectRules() {
    }

    /** Applies the completion times and checks every rule; returns the project as it should be stored. */
    static Project apply(Project before, Project after, Instant now) {
        Project stamped = stampCompletion(before, after, now);
        checkFrozenDates(before, stamped);
        checkMilestoneDates(before, stamped);
        checkDependencies(stamped);
        checkBlocking(before, stamped);
        return stamped;
    }

    private static Project stampCompletion(Project before, Project after, Instant now) {
        List<Milestone> milestones = after.milestones().stream().map(milestone -> {
            Optional<Milestone> previous = find(before, milestone.id());
            CompletionStatus previousStatus = previous.map(Milestone::status).orElse(null);
            return milestone.withStatus(milestone.status(), completedAt(milestone.status(), previousStatus,
                            previous.map(Milestone::completedAt).orElse(null), now))
                    .withStatusChangedAt(statusChangedAt(previous.isEmpty(), milestone.status(), previousStatus,
                            previous.map(Milestone::statusChangedAt).orElse(null), now));
        }).toList();
        return after.withMilestones(milestones)
                .withCompletedAt(completedAt(after.status(), before == null ? null : before.status(),
                        before == null ? null : before.completedAt(), now))
                .withStatusChangedAt(statusChangedAt(before == null, after.status(),
                        before == null ? null : before.status(), before == null ? null : before.statusChangedAt(),
                        now));
    }

    /**
     * Now for something new or whose status just changed; otherwise the earlier time, which is unknown (null) for
     * records saved before status changes were tracked.
     */
    static Instant statusChangedAt(boolean created, CompletionStatus status, CompletionStatus previous,
                                   Instant previousAt, Instant now) {
        return created || status != previous ? now : previousAt;
    }

    /** Now when something just became COMPLETE; the earlier time while it stays complete; none otherwise. */
    static Instant completedAt(CompletionStatus status, CompletionStatus previous, Instant previousAt, Instant now) {
        if (status != CompletionStatus.COMPLETE) {
            return null;
        }
        return previous == CompletionStatus.COMPLETE ? previousAt : now;
    }

    private static void checkFrozenDates(Project before, Project after) {
        if (before == null) {
            return;
        }
        boolean projectComplete = before.status() == CompletionStatus.COMPLETE
                && after.status() == CompletionStatus.COMPLETE;
        if (projectComplete && !sameDates(before.startDate(), before.endDate(), after.startDate(), after.endDate())) {
            throw new ConflictException("The dates of a completed project are frozen; reopen it to change them");
        }
        for (Milestone milestone : after.milestones()) {
            Optional<Milestone> previous = find(before, milestone.id());
            if (previous.isEmpty() || sameDates(previous.get().startDate(), previous.get().endDate(),
                    milestone.startDate(), milestone.endDate())) {
                continue;
            }
            if (projectComplete) {
                throw new ConflictException("The milestones of a completed project have frozen dates; reopen the"
                        + " project to change them");
            }
            if (previous.get().status() == CompletionStatus.COMPLETE
                    && milestone.status() == CompletionStatus.COMPLETE) {
                throw new ConflictException("The dates of completed milestone '" + milestone.name()
                        + "' are frozen; reopen it to change them");
            }
        }
    }

    /** Checks new milestones and changed dates, or every milestone when the project's own dates changed. */
    private static void checkMilestoneDates(Project before, Project after) {
        boolean projectDatesChanged = before == null
                || !sameDates(before.startDate(), before.endDate(), after.startDate(), after.endDate());
        for (Milestone milestone : after.milestones()) {
            Optional<Milestone> previous = find(before, milestone.id());
            boolean changed = previous.isEmpty() || !sameDates(previous.get().startDate(), previous.get().endDate(),
                    milestone.startDate(), milestone.endDate());
            if ((projectDatesChanged || changed) && (milestone.startDate().isBefore(after.startDate())
                    || milestone.endDate().isAfter(after.endDate()))) {
                throw new IllegalArgumentException("Milestone '" + milestone.name() + "' must fall within the"
                        + " project's dates (" + after.startDate() + " to " + after.endDate() + ")");
            }
        }
    }

    private static void checkDependencies(Project after) {
        Map<String, Milestone> byId = byId(after);
        for (Milestone milestone : after.milestones()) {
            for (String dependency : milestone.dependsOn()) {
                if (dependency.equals(milestone.id())) {
                    throw new IllegalArgumentException("Milestone '" + milestone.name()
                            + "' cannot depend on itself");
                }
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("Milestone '" + milestone.name()
                            + "' depends on a milestone that is not part of this project: " + dependency);
                }
            }
        }
        Map<String, Boolean> finished = new HashMap<>();
        for (Milestone milestone : after.milestones()) {
            List<String> cycle = findCycle(milestone.id(), byId, finished, new ArrayList<>());
            if (cycle != null) {
                throw new IllegalArgumentException("Milestone dependencies cannot form a cycle: "
                        + String.join(" → ", cycle.stream().map(id -> byId.get(id).name()).toList()));
            }
        }
    }

    /** Depth-first search; returns the milestones on a cycle reachable from {@code id}, or null. */
    private static List<String> findCycle(String id, Map<String, Milestone> byId, Map<String, Boolean> finished,
                                          List<String> path) {
        if (Boolean.TRUE.equals(finished.get(id))) {
            return null;
        }
        int seen = path.indexOf(id);
        if (seen >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(seen, path.size()));
            cycle.add(id);
            return cycle;
        }
        path.add(id);
        for (String dependency : byId.get(id).dependsOn()) {
            List<String> cycle = findCycle(dependency, byId, finished, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        finished.put(id, true);
        return null;
    }

    private static void checkBlocking(Project before, Project after) {
        Map<String, Milestone> byId = byId(after);
        for (Milestone milestone : after.milestones()) {
            Optional<Milestone> previous = find(before, milestone.id());
            boolean changed = previous.isEmpty() || previous.get().status() != milestone.status()
                    || !previous.get().dependsOn().equals(milestone.dependsOn());
            if (changed && milestone.started()) {
                List<String> incomplete = milestone.dependsOn().stream()
                        .map(byId::get)
                        .filter(dependency -> dependency.status() != CompletionStatus.COMPLETE)
                        .map(Milestone::name)
                        .toList();
                if (!incomplete.isEmpty()) {
                    throw new ConflictException("Milestone '" + milestone.name() + "' cannot start or complete"
                            + " until the milestones it depends on are complete: " + String.join(", ", incomplete));
                }
            }
            boolean reopened = previous.isPresent() && previous.get().status() == CompletionStatus.COMPLETE
                    && milestone.status() != CompletionStatus.COMPLETE;
            if (reopened) {
                List<String> startedDependents = dependentsOf(after, milestone.id()).stream()
                        .filter(Milestone::started).map(Milestone::name).toList();
                if (!startedDependents.isEmpty()) {
                    throw new ConflictException("Milestone '" + milestone.name() + "' cannot be reopened while"
                            + " milestones that depend on it have started: " + String.join(", ", startedDependents));
                }
            }
        }
    }

    /** Milestones of the project whose dependsOn includes {@code milestoneId}. */
    static List<Milestone> dependentsOf(Project project, String milestoneId) {
        return project.milestones().stream()
                .filter(milestone -> milestone.dependsOn().contains(milestoneId))
                .toList();
    }

    private static boolean sameDates(Object startBefore, Object endBefore, Object startAfter, Object endAfter) {
        return Objects.equals(startBefore, startAfter) && Objects.equals(endBefore, endAfter);
    }

    private static Optional<Milestone> find(Project project, String milestoneId) {
        return project == null ? Optional.empty()
                : project.milestones().stream().filter(milestone -> milestone.id().equals(milestoneId)).findFirst();
    }

    private static Map<String, Milestone> byId(Project project) {
        Map<String, Milestone> byId = new HashMap<>();
        project.milestones().forEach(milestone -> byId.put(milestone.id(), milestone));
        return byId;
    }
}
