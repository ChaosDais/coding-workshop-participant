package com.acme.models;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Time an employee spent on a milestone, entered by hand or measured with a timer.
 * A running timer is an entry without an end time, hours or cost yet.
 * The hourly rate is copied from the employee's salary when the time is logged, so later pay changes do not
 * rewrite what earlier work cost. An admin can correct the hours afterwards; the entry then records who corrected
 * it, when and why, and the audit log keeps the values from before.
 */
public record TimeEntry(String id, String projectId, String milestoneId, String userId, Source source, String note,
                        Instant startedAt, Instant endedAt, BigDecimal hours, BigDecimal hourlyRate, BigDecimal cost,
                        Instant loggedAt, Instant correctedAt, String correctedBy, String correctionReason) {
    public enum Source {
        MANUAL, TIMER
    }

    public boolean running() {
        return source == Source.TIMER && endedAt == null;
    }

    /** The entry without pay figures, for viewers who may not see what someone earns. */
    public TimeEntry withoutPay() {
        return new TimeEntry(id, projectId, milestoneId, userId, source, note, startedAt, endedAt, hours, null, null,
                loggedAt, correctedAt, correctedBy, correctionReason);
    }
}
