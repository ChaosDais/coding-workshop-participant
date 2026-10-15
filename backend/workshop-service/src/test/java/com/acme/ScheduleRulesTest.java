package com.acme;

import com.acme.models.Project;
import com.acme.repos.InMemoryProjectRepository;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduling rules: nothing starts in the past, nothing starts before the unfinished work it depends on ends, and
 * expected hours fit in 24 hours a day per person. The test clock reads 2026-10-14.
 */
class ScheduleRulesTest {
    private final InMemoryProjectRepository repository = new InMemoryProjectRepository();
    private final TestApp app = new TestApp(repository);
    private String token;
    private String managerId;
    private String employeeId;
    private String otherEmployeeId;
    private String projectId;

    @BeforeEach
    void setUp() {
        token = app.setUpAdmin();
        managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);
        otherEmployeeId = app.createUser(token, "other@example.com", "EMPLOYEE", managerId);
        projectId = id(send("POST", "/projects", project("Apollo", "2026-11-01", "2027-06-30", "")));
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    private static String id(Map<String, Object> response) {
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    /** A project body; {@code extra} adds fields such as "\"expectedHours\": 10, ". */
    private String project(String name, String start, String end, String extra) {
        return """
                {%s"name": "%s", "startDate": "%s", "endDate": "%s", "manager": "%s", "budget": 1000,
                 "status": "TODO"}""".formatted(extra, name, start, end, managerId);
    }

    private static String milestone(String name, String start, String end, String extra) {
        return """
                {%s"name": "%s", "startDate": "%s", "endDate": "%s", "status": "TODO"}"""
                .formatted(extra, name, start, end);
    }

    private Map<String, Object> addMilestone(String body) {
        return send("POST", "/projects/" + projectId + "/milestones", body);
    }

    @Test
    void projectsAndMilestonesCannotStartInThePast() {
        assertEquals("Project 'Late' cannot start in the past (today is 2026-10-14)",
                error(send("POST", "/projects", project("Late", "2026-10-13", "2026-12-31", ""))));
        // Today is fine.
        id(send("POST", "/projects", project("Today", "2026-10-14", "2026-12-31", "")));

        assertEquals("Milestone 'Kickoff' cannot start in the past (today is 2026-10-14)",
                error(addMilestone(milestone("Kickoff", "2026-10-01", "2026-11-15", ""))));
        // Moving an existing start date into the past is refused too.
        assertEquals(400, status(send("PUT", "/projects/" + projectId,
                project("Apollo", "2026-10-01", "2027-06-30", ""))));
    }

    @Test
    void projectsThatAlreadyStartedStayEditable() {
        // Saved before the rule, or simply started last month: the start date is in the past.
        Project stored = repository.findById(projectId).orElseThrow();
        assertTrue(repository.replace(new Project(stored.id(), stored.name(), stored.description(),
                LocalDate.of(2026, 9, 1), stored.endDate(), 0, stored.manager(), stored.budget(), stored.status(),
                stored.milestones(), null, stored.dependsOn(), null, null)));

        // Editing something else keeps the past start date.
        assertEquals(200, status(send("PUT", "/projects/" + projectId,
                project("Apollo II", "2026-09-01", "2027-06-30", ""))));
    }

    @Test
    void milestonesStartOnceTheUnfinishedMilestonesTheyDependOnEnd() {
        String design = id(addMilestone(milestone("Design", "2026-11-01", "2026-11-30", "")));

        assertEquals("Milestone 'Build' cannot start before 'Design', which it depends on, ends (2026-11-30)",
                error(addMilestone(milestone("Build", "2026-11-15", "2026-12-31",
                        "\"dependsOn\": [\"" + design + "\"], "))));
        // Starting the day the dependency ends is fine.
        String build = id(addMilestone(milestone("Build", "2026-11-30", "2026-12-31",
                "\"dependsOn\": [\"" + design + "\"], ")));

        // The dependency cannot be stretched past the start of what waits on it.
        assertEquals("Milestone 'Design' cannot end after 'Build', which depends on it, starts (2026-11-30)",
                error(send("PUT", "/projects/" + projectId + "/milestones/" + design,
                        milestone("Design", "2026-11-01", "2026-12-10", ""))));

        // Once the dependency is complete, its end date no longer holds anything back.
        assertEquals(200, status(send("PUT", "/projects/" + projectId + "/milestones/" + design + "/status",
                "{\"status\": \"COMPLETE\"}")));
        assertEquals(200, status(send("PUT", "/projects/" + projectId + "/milestones/" + build,
                milestone("Build", "2026-11-20", "2026-12-31", "\"dependsOn\": [\"" + design + "\"], "))));
    }

    @Test
    void projectsStartOnceTheUnfinishedProjectsTheyDependOnEnd() {
        String dependsOnApollo = "\"dependsOn\": [\"" + projectId + "\"], ";
        assertEquals("Project 'Gemini' cannot start before 'Apollo', which it depends on, ends (2027-06-30)",
                error(send("POST", "/projects", project("Gemini", "2027-01-01", "2027-12-31", dependsOnApollo))));
        id(send("POST", "/projects", project("Gemini", "2027-06-30", "2027-12-31", dependsOnApollo)));

        assertEquals("Project 'Apollo' cannot end after 'Gemini', which depends on it, starts (2027-06-30)",
                error(send("PUT", "/projects/" + projectId, project("Apollo", "2026-11-01", "2027-07-31", ""))));
    }

    @Test
    void expectedHoursFitInTwentyFourHoursADayPerPerson() {
        // Two days, nobody assigned yet: counted as one person, so at most 48 hours.
        id(addMilestone(milestone("Sprint", "2026-11-02", "2026-11-03", "\"expectedHours\": 48, ")));
        assertEquals("The expected hours of milestone 'Crunch' are more than possible: at most 48 h (24 h a day × 2"
                        + " days × 1 person, counting its assignees)",
                error(addMilestone(milestone("Crunch", "2026-11-02", "2026-11-03", "\"expectedHours\": 49, "))));

        // Two people on it double what is possible.
        String pair = "\"assignedEmployees\": [\"" + employeeId + "\", \"" + otherEmployeeId + "\"], ";
        Map<String, Object> shared = addMilestone(milestone("Pairing", "2026-11-05", "2026-11-06",
                pair + "\"expectedHours\": 96, "));
        assertEquals(201, status(shared), () -> String.valueOf(shared.get("body")));
        assertEquals(400, status(addMilestone(milestone("Pairing 2", "2026-11-09", "2026-11-10",
                pair + "\"expectedHours\": 97, "))));

        // A project counts everyone assigned to its milestones: here 2 people over 242 days.
        JsonObject updated = body(send("PUT", "/projects/" + projectId,
                project("Apollo", "2026-11-01", "2027-06-30", "\"expectedHours\": 11616, "))).getAsJsonObject();
        assertEquals(11616.0, updated.get("expectedHours").getAsDouble());
        assertTrue(error(send("PUT", "/projects/" + projectId,
                project("Apollo", "2026-11-01", "2027-06-30", "\"expectedHours\": 11617, ")))
                .startsWith("The expected hours of project 'Apollo' are more than possible: at most 11616 h"));
    }
}
