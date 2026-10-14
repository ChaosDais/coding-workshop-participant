package com.acme.repos;

import com.acme.errors.ConflictException;
import com.acme.models.TimeEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double for the PostgreSQL store; adds logged hours to milestones in the in-memory project store. */
public class InMemoryTimeEntryStore implements TimeEntryStore {
    private final List<TimeEntry> entries = new ArrayList<>();
    private final InMemoryProjectStore projects;

    public InMemoryTimeEntryStore(InMemoryProjectStore projects) {
        this.projects = projects;
    }

    @Override
    public synchronized void insertLogged(TimeEntry entry) {
        entries.add(entry);
        addProgress(entry.projectId(), entry.milestoneId(), entry.hours());
    }

    @Override
    public synchronized void insertRunning(TimeEntry entry) {
        if (findRunning(entry.userId()).isPresent()) {
            throw new ConflictException("A timer is already running; stop it before starting another");
        }
        entries.add(entry);
    }

    @Override
    public synchronized Optional<TimeEntry> findRunning(String userId) {
        return entries.stream().filter(entry -> entry.userId().equals(userId) && entry.running()).findFirst();
    }

    @Override
    public synchronized Optional<TimeEntry> findById(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    @Override
    public synchronized boolean correct(TimeEntry corrected, BigDecimal previousHours) {
        for (int index = 0; index < entries.size(); index++) {
            TimeEntry stored = entries.get(index);
            if (stored.id().equals(corrected.id()) && stored.hours() != null
                    && stored.hours().compareTo(previousHours) == 0) {
                entries.set(index, corrected);
                addProgress(corrected.projectId(), corrected.milestoneId(),
                        corrected.hours().subtract(previousHours));
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean stop(TimeEntry finished) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(finished.id()) && entries.get(index).running()) {
                entries.set(index, finished);
                addProgress(finished.projectId(), finished.milestoneId(), finished.hours());
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized List<TimeEntry> findForMilestone(String projectId, String milestoneId) {
        return entries.stream()
                .filter(entry -> entry.projectId().equals(projectId) && entry.milestoneId().equals(milestoneId))
                .sorted(Comparator.comparing(entry -> entry.startedAt() != null ? entry.startedAt() : entry.loggedAt()))
                .toList();
    }

    @Override
    public synchronized Map<String, BigDecimal> totalCostByProject() {
        Map<String, BigDecimal> totals = new HashMap<>();
        entries.stream().filter(entry -> entry.cost() != null)
                .forEach(entry -> totals.merge(entry.projectId(), entry.cost(), BigDecimal::add));
        return totals;
    }

    /** Same rounding and floor at zero as the PostgreSQL store. */
    private void addProgress(String projectId, String milestoneId, BigDecimal hours) {
        projects.updateMilestone(projectId, milestoneId, milestone -> milestone.withProgress(
                BigDecimal.valueOf(milestone.progressTime()).add(hours).setScale(2, RoundingMode.HALF_UP)
                        .max(BigDecimal.ZERO).doubleValue()));
    }
}
