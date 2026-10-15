package com.acme.services;

import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.models.Role;
import com.acme.models.TimeEntry;
import com.acme.models.User;
import com.acme.repos.ProjectRepository;
import com.acme.repos.TimeEntryRepository;
import com.acme.repos.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * People log their own time on milestones, either as a number of hours or with a timer: employees on milestones
 * they are assigned to, managers on the projects they manage, admins anywhere. The responses leave out pay
 * figures, which only admins see in the time list.
 * Logged hours are added to the milestone's progress, and their cost (hours times the hourly rate derived from the
 * worker's annual salary) counts toward what the project has spent. Admins can correct logged hours.
 * Each person works toward one milestone at a time: only one timer can run, and hours cannot be entered by hand
 * while it does, so the same time is never counted twice. A timer covers at most one 8-hour workday.
 */
public class TimeService {
    /** 40 hours a week for 52 weeks: turns an annual salary into an hourly rate. */
    static final BigDecimal WORK_HOURS_PER_YEAR = new BigDecimal("2080");
    /** One manual entry covers at most a day. */
    static final BigDecimal MAX_MANUAL_HOURS = new BigDecimal("24");
    /** A standard workday; a timer left running longer is closed at this length. */
    public static final Duration MAX_TIMER = Duration.ofHours(8);
    private static final BigDecimal SMALLEST_ENTRY = new BigDecimal("0.01");
    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal("3600");
    private static final String PROJECT_NOT_FOUND = "This project was not found; it may have been archived";

    private final TimeEntryRepository entries;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final AuditService auditService;
    private final Clock clock;

    public TimeService(TimeEntryRepository entries, ProjectRepository projects, UserRepository users,
                       AuditService auditService, Clock clock) {
        this.entries = entries;
        this.projects = projects;
        this.users = users;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Logs hours entered by hand, rounded to two decimals; 0 < hours <= 24. */
    public TimeEntry logHours(String projectId, String milestoneId, BigDecimal hours, String note, User worker) {
        requireWorkable(projectId, milestoneId, worker);
        if (hours == null || hours.signum() <= 0 || hours.compareTo(MAX_MANUAL_HOURS) > 0) {
            throw new IllegalArgumentException("hours must be more than 0 and at most " + MAX_MANUAL_HOURS);
        }
        BigDecimal rounded = hours.setScale(2, RoundingMode.HALF_UP).max(SMALLEST_ENTRY);
        activeTimer(worker).ifPresent(running -> {
            throw new ConflictException("Stop your running timer on " + milestoneName(running)
                    + " before logging hours by hand");
        });
        BigDecimal rate = hourlyRate(worker);
        TimeEntry entry = new TimeEntry(UUID.randomUUID().toString(), projectId, milestoneId, worker.id(),
                TimeEntry.Source.MANUAL, blankToNull(note), null, null, rounded, rate, cost(rounded, rate),
                clock.instant(), null, null, null);
        entries.insertLogged(entry);
        audit("time_logged", entry, worker, Map.of());
        return entry.withoutPay();
    }

    public TimeEntry startTimer(String projectId, String milestoneId, String note, User worker) {
        requireWorkable(projectId, milestoneId, worker);
        // Closes a timer that already ran out, so it does not block this one.
        activeTimer(worker);
        Instant now = clock.instant();
        TimeEntry timer = new TimeEntry(UUID.randomUUID().toString(), projectId, milestoneId, worker.id(),
                TimeEntry.Source.TIMER, blankToNull(note), now, null, null, null, null, now, null, null, null);
        // The database refuses a second running timer, so two simultaneous starts cannot both succeed.
        entries.insertRunning(timer);
        audit("timer_started", timer, worker, Map.of());
        return timer.withoutPay();
    }

    /**
     * Stops the running timer and logs the elapsed time, at most 8 hours, rounded to two decimals of an hour.
     * The time is logged even if the milestone was archived or reassigned while the timer ran, because the work
     * was still done.
     */
    public TimeEntry stopTimer(User worker) {
        // A conflict, not 404: CloudFront would turn a 404 into the frontend's index page.
        TimeEntry running = entries.findRunning(worker.id())
                .orElseThrow(() -> new ConflictException("No timer is running"));
        return finish(running, worker, "timer_stopped").withoutPay();
    }

    /** The user's running timer, if any; one that has reached 8 hours is closed first, and then there is none. */
    public Optional<TimeEntry> runningTimer(User worker) {
        return activeTimer(worker).map(TimeEntry::withoutPay);
    }

    /**
     * Sets a logged entry's hours (0 to 24, so an entry logged by mistake can be zeroed) and optionally its note.
     * The cost is recalculated with the rate the entry was logged at, the milestone's progress moves by the
     * difference, and the audit log records the hours from before.
     */
    public TimeEntry correct(String entryId, BigDecimal hours, String note, String reason, User admin) {
        TimeEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new NotFoundException("This time entry was not found"));
        if (entry.running()) {
            throw new ConflictException("This timer is still running; it can be corrected once it has stopped");
        }
        if (hours == null || hours.signum() < 0 || hours.compareTo(MAX_MANUAL_HOURS) > 0) {
            throw new IllegalArgumentException("hours must be between 0 and " + MAX_MANUAL_HOURS);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        BigDecimal rounded = hours.setScale(2, RoundingMode.HALF_UP);
        TimeEntry corrected = new TimeEntry(entry.id(), entry.projectId(), entry.milestoneId(), entry.userId(),
                entry.source(), note == null ? entry.note() : blankToNull(note), entry.startedAt(), entry.endedAt(),
                rounded, entry.hourlyRate(), cost(rounded, entry.hourlyRate()), entry.loggedAt(), clock.instant(),
                admin.email(), reason.trim());
        if (!entries.correct(corrected, entry.hours())) {
            throw new ConflictException("This entry was corrected by someone else just now; reload it and retry");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousHours", entry.hours().toPlainString());
        details.put("previousCost", entry.cost().toPlainString());
        details.put("reason", corrected.correctionReason());
        audit("time_corrected", corrected, admin, details);
        return corrected;
    }

    /**
     * Time logged on a milestone. Admins see everything; the project's manager sees everyone's hours without pay;
     * an assigned employee sees only their own entries, without pay.
     */
    public List<TimeEntry> findForMilestone(String projectId, String milestoneId, User actor) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException(PROJECT_NOT_FOUND));
        Milestone milestone = requireMilestone(project, milestoneId);
        List<TimeEntry> logged = entries.findForMilestone(projectId, milestoneId);
        return switch (actor.role()) {
            case ADMIN -> logged;
            case MANAGER -> {
                if (!actor.id().equals(project.manager())) {
                    throw new ForbiddenException("Access denied");
                }
                yield logged.stream().map(TimeEntry::withoutPay).toList();
            }
            case EMPLOYEE -> {
                if (!milestone.assignedEmployees().contains(actor.id())) {
                    throw new ForbiddenException("Access denied");
                }
                yield logged.stream().filter(entry -> entry.userId().equals(actor.id()))
                        .map(TimeEntry::withoutPay).toList();
            }
        };
    }

    /** The running timer, after closing it at 8 hours if it has run that long. */
    private Optional<TimeEntry> activeTimer(User worker) {
        Optional<TimeEntry> running = entries.findRunning(worker.id());
        if (running.isPresent() && !clock.instant().isBefore(running.get().startedAt().plus(MAX_TIMER))) {
            finish(running.get(), worker, "timer_capped");
            return Optional.empty();
        }
        return running;
    }

    /** Ends a running timer now, or at 8 hours if it has run longer, and logs its time. */
    private TimeEntry finish(TimeEntry running, User worker, String action) {
        Instant cap = running.startedAt().plus(MAX_TIMER);
        Instant end = clock.instant().isAfter(cap) ? cap : clock.instant();
        BigDecimal seconds = BigDecimal.valueOf(Duration.between(running.startedAt(), end).toSeconds());
        BigDecimal hours = seconds.divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP).max(SMALLEST_ENTRY);
        BigDecimal rate = hourlyRate(worker);
        TimeEntry finished = new TimeEntry(running.id(), running.projectId(), running.milestoneId(), running.userId(),
                running.source(), running.note(), running.startedAt(), end, hours, rate, cost(hours, rate),
                clock.instant(), null, null, null);
        if (!entries.stop(finished)) {
            throw new NotFoundException("No timer is running");
        }
        audit(action, finished, worker, Map.of());
        return finished;
    }

    /**
     * Who may log their own time on a milestone: an employee assigned to it, the project's manager, or an admin.
     * The milestone must not be complete or blocked by unfinished dependencies.
     */
    private void requireWorkable(String projectId, String milestoneId, User worker) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException(PROJECT_NOT_FOUND));
        Milestone milestone = requireMilestone(project, milestoneId);
        boolean allowed = switch (worker.role()) {
            case ADMIN -> true;
            case MANAGER -> worker.id().equals(project.manager());
            case EMPLOYEE -> milestone.assignedEmployees().contains(worker.id());
        };
        if (!allowed) {
            throw new ForbiddenException(worker.role() == Role.MANAGER
                    ? "You can only log time on projects you manage"
                    : "You can only log time on milestones you are assigned to");
        }
        if (milestone.status() == CompletionStatus.COMPLETE) {
            throw new ConflictException("This milestone is complete; time can no longer be logged on it");
        }
        // Logging time is working on the milestone, which its dependencies have to allow.
        List<String> blockers = project.milestones().stream()
                .filter(other -> milestone.dependsOn().contains(other.id()))
                .filter(other -> other.status() != CompletionStatus.COMPLETE)
                .map(Milestone::name)
                .toList();
        if (!blockers.isEmpty()) {
            throw new ConflictException("This milestone cannot be worked on until the milestones it depends on are"
                    + " complete: " + String.join(", ", blockers));
        }
        List<String> blockingProjects = project.dependsOn().stream()
                .map(dependency -> projects.findById(dependency))
                .filter(found -> found.isEmpty() || found.get().status() != CompletionStatus.COMPLETE)
                .map(found -> found.map(Project::name).orElse("(archived project)"))
                .toList();
        if (!blockingProjects.isEmpty()) {
            throw new ConflictException("This project cannot be worked on until the projects it depends on are"
                    + " complete: " + String.join(", ", blockingProjects));
        }
    }

    private static Milestone requireMilestone(Project project, String milestoneId) {
        return project.milestones().stream()
                .filter(milestone -> milestone.id().equals(milestoneId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("This milestone was not found; it may have been archived"));
    }

    /** Annual salary / 2080, to four decimals; someone without a salary costs nothing. */
    private BigDecimal hourlyRate(User worker) {
        BigDecimal salary = users.findById(worker.id()).map(User::salary).orElse(null);
        return salary == null ? BigDecimal.ZERO : salary.divide(WORK_HOURS_PER_YEAR, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal cost(BigDecimal hours, BigDecimal rate) {
        return hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The name of the milestone a timer or entry is on, for messages; "another milestone" if it is gone. */
    private String milestoneName(TimeEntry entry) {
        return projects.findById(entry.projectId())
                .flatMap(project -> project.milestones().stream()
                        .filter(milestone -> milestone.id().equals(entry.milestoneId())).findFirst())
                .map(milestone -> "'" + milestone.name() + "'")
                .orElse("another milestone");
    }

    private void audit(String action, TimeEntry entry, User actor, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("timeEntryId", entry.id());
        details.put("employeeId", entry.userId());
        users.findById(entry.userId()).ifPresent(worker -> details.put("employeeName", worker.name()));
        details.put("projectId", entry.projectId());
        details.put("milestoneId", entry.milestoneId());
        // Names are stored too, so the log stays readable after renames or archiving.
        projects.findById(entry.projectId()).ifPresent(project -> {
            details.put("projectName", project.name());
            project.milestones().stream().filter(milestone -> milestone.id().equals(entry.milestoneId()))
                    .findFirst().ifPresent(milestone -> details.put("milestoneName", milestone.name()));
        });
        if (entry.hours() != null) {
            details.put("hours", entry.hours().toPlainString());
        }
        details.putAll(extra);
        auditService.recordAction(action, actor, details);
    }
}
