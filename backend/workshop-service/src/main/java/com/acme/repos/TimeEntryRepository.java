package com.acme.repos;

import com.acme.models.TimeEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Logged time and running timers; entries are never deleted. */
public interface TimeEntryRepository {
    /** Stores finished time and adds its hours to the milestone's progress, in one transaction. */
    void insertLogged(TimeEntry entry);

    /** Stores a running timer; fails with ConflictException when the user already has one running. */
    void insertRunning(TimeEntry entry);

    Optional<TimeEntry> findRunning(String userId);

    Optional<TimeEntry> findById(String id);

    /**
     * Replaces a finished entry's hours, cost, note and correction details, and moves the milestone's progress by
     * the difference, in one transaction. Returns false when the entry's hours are no longer
     * {@code previousHours}, i.e. someone else corrected it first.
     */
    boolean correct(TimeEntry corrected, BigDecimal previousHours);

    /**
     * Finishes a running timer with the entry's end time, hours, rate and cost and adds the hours to the
     * milestone's progress, in one transaction. Returns false when the timer was already stopped.
     */
    boolean stop(TimeEntry finished);

    /** Every entry on a milestone, running timers included, oldest first. */
    List<TimeEntry> findForMilestone(String projectId, String milestoneId);

    /** A person's finished entries, everywhere, oldest first; running timers are left out. */
    List<TimeEntry> findLoggedForUser(String userId);

    /** Total cost of the logged time, by project id; projects without logged time are absent. */
    Map<String, BigDecimal> totalCostByProject();
}
