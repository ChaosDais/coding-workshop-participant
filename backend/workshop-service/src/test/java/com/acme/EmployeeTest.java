package com.acme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Employees see only their assigned work and their team, edit only their own name and email, move their
 * milestones to AWAITING_REVIEW, and log time that adds to milestone progress and project spending.
 */
class EmployeeTest {
    private final TestApp app = new TestApp();
    private String adminToken;
    private String managerId;
    private String managerToken;
    private String employeeId;
    private String employeeToken;
    private String teammateId;
    private String outsiderId;
    private String projectId;
    private String milestoneId;
    private String teammateMilestoneId;
    private String otherProjectId;

    @BeforeEach
    void setUp() {
        adminToken = app.setUpAdmin();
        managerId = app.createUser(adminToken, "manager@example.com", "MANAGER", null);
        String otherManagerId = app.createUser(adminToken, "other-manager@example.com", "MANAGER", null);
        // 104,000 a year is exactly 50 an hour over 2,080 working hours.
        employeeId = createEmployee("employee@example.com", managerId, "104000");
        teammateId = createEmployee("teammate@example.com", managerId, null);
        outsiderId = createEmployee("outsider@example.com", otherManagerId, "90000");

        projectId = createProject("Apollo", managerId);
        milestoneId = addMilestone(projectId, "Design", employeeId);
        teammateMilestoneId = addMilestone(projectId, "Build", teammateId);
        otherProjectId = createProject("Gemini", managerId);
        addMilestone(otherProjectId, "Launch", teammateId);

        managerToken = app.signIn("manager@example.com", PASSWORD).get("accessToken").getAsString();
        employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
    }

    private String createEmployee(String email, String manager, String salary) {
        Map<String, Object> response = app.send("POST", "/users", """
                {"email": "%s", "name": "%s", "role": "EMPLOYEE", "managerId": "%s", "password": "%s"%s}"""
                .formatted(email, email, manager, PASSWORD, salary == null ? "" : ", \"salary\": " + salary),
                adminToken);
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private String createProject(String name, String manager) {
        Map<String, Object> response = app.send("POST", "/projects", """
                {"name": "%s", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 0,
                 "manager": "%s", "budget": 5000, "status": "IN_PROGRESS"}""".formatted(name, manager), adminToken);
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private String addMilestone(String project, String name, String assignee) {
        Map<String, Object> response = app.send("POST", "/projects/" + project + "/milestones", """
                {"name": "%s", "startDate": "2026-01-01", "endDate": "2026-02-01", "progressTime": 0,
                 "assignedEmployees": ["%s"], "status": "IN_PROGRESS"}""".formatted(name, assignee), adminToken);
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, employeeToken);
    }

    private String milestonePath(String milestone) {
        return "/projects/" + projectId + "/milestones/" + milestone;
    }

    private JsonObject milestoneAsAdmin(String milestone) {
        return body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .getAsJsonArray("milestones").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(found -> found.get("id").getAsString().equals(milestone)).findFirst().orElseThrow();
    }

    @Test
    void employeesSeeOnlyTheirAssignedProjectsWithAllTheirMilestonesButNoMoney() {
        List<JsonObject> visible = body(send("GET", "/projects", null)).getAsJsonArray().asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        assertEquals(List.of(projectId), visible.stream().map(project -> project.get("id").getAsString()).toList());
        JsonObject project = visible.get(0);
        // Every milestone of the project, including the teammate's, so dependencies and progress make sense.
        assertEquals(List.of(milestoneId, teammateMilestoneId), project.getAsJsonArray("milestones").asList()
                .stream().map(milestone -> milestone.getAsJsonObject().get("id").getAsString()).toList());
        assertFalse(project.has("budget") || project.has("spent"));
        // Other people's logged hours stay private, and only their own milestones can be changed.
        assertEquals(403, status(send("GET", milestonePath(teammateMilestoneId) + "/time", null)));

        assertEquals(200, status(send("GET", "/projects/" + projectId, null)));
        assertEquals("Access denied", error(send("GET", "/projects/" + otherProjectId, null)));
        assertEquals(403, status(send("GET", "/projects?archived=true", null)));
    }

    @Test
    void employeesSeeTheirManagerAndTeammatesWithoutSalaries() {
        List<JsonObject> team = body(send("GET", "/users", null)).getAsJsonArray().asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        assertEquals(List.of(employeeId, managerId, teammateId).stream().sorted().toList(),
                team.stream().map(user -> user.get("id").getAsString()).sorted().toList());
        assertTrue(team.stream().noneMatch(user -> user.has("salary")));

        assertEquals(200, status(send("GET", "/users/" + managerId, null)));
        assertFalse(body(send("GET", "/users/" + employeeId, null)).getAsJsonObject().has("salary"));
        assertEquals(403, status(send("GET", "/users/" + outsiderId, null)));
        assertEquals(403, status(send("GET", "/users?archived=true", null)));
    }

    @Test
    void employeesEditOnlyTheirOwnNameAndEmail() {
        Map<String, Object> updated = send("PUT", "/auth/me",
                "{\"name\": \"Eli\", \"email\": \"Eli@Example.com\", \"role\": \"ADMIN\", \"salary\": 1}");
        assertEquals(200, status(updated));
        JsonObject me = body(app.send("GET", "/users/" + employeeId, null, adminToken)).getAsJsonObject();
        assertEquals("Eli", me.get("name").getAsString());
        assertEquals("eli@example.com", me.get("email").getAsString());
        // Role and salary in the body are ignored; only an admin can change them.
        assertEquals("EMPLOYEE", me.get("role").getAsString());
        assertEquals(104000, me.get("salary").getAsInt());

        assertEquals(409, status(send("PUT", "/auth/me",
                "{\"name\": \"Eli\", \"email\": \"teammate@example.com\"}")));
        assertEquals("email must be a valid email address", error(send("PUT", "/auth/me",
                "{\"name\": \"Eli\", \"email\": \"nope\"}")));
    }

    @Test
    void employeesSendTheirMilestonesForReviewButCannotCompleteThem() {
        Map<String, Object> submitted = send("PUT", milestonePath(milestoneId) + "/status",
                "{\"status\": \"AWAITING_REVIEW\"}");
        assertEquals(200, status(submitted));
        assertEquals("AWAITING_REVIEW", milestoneAsAdmin(milestoneId).get("status").getAsString());

        assertEquals("Employees can only set a milestone to AWAITING_REVIEW",
                error(send("PUT", milestonePath(milestoneId) + "/status", "{\"status\": \"COMPLETE\"}")));
        assertEquals(403, status(send("PUT", milestonePath(teammateMilestoneId) + "/status",
                "{\"status\": \"AWAITING_REVIEW\"}")));
        assertEquals(400, status(send("PUT", milestonePath(milestoneId) + "/status", "{\"status\": \"DONE\"}")));

        assertEquals(200, status(app.send("PUT", milestonePath(milestoneId) + "/status",
                "{\"status\": \"COMPLETE\"}", managerToken)));
        assertEquals("This milestone is already complete",
                error(send("PUT", milestonePath(milestoneId) + "/status", "{\"status\": \"AWAITING_REVIEW\"}")));
    }

    @Test
    void loggedHoursAddToProgressAndProjectSpending() {
        Map<String, Object> logged = send("POST", milestonePath(milestoneId) + "/time",
                "{\"hours\": 2, \"note\": \"Wireframes\"}");
        assertEquals(201, status(logged));
        JsonObject entry = body(logged).getAsJsonObject();
        assertEquals("2.00", entry.get("hours").getAsString());
        assertFalse(entry.has("cost") || entry.has("hourlyRate"));

        assertEquals(2.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        // 2 hours at 104,000 / 2,080 = 50 an hour.
        assertEquals("100.00", body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .get("spent").getAsString());
        assertEquals("100.00", body(app.send("GET", "/projects/" + projectId, null, managerToken)).getAsJsonObject()
                .get("spent").getAsString());

        JsonObject adminView = body(app.send("GET", milestonePath(milestoneId) + "/time", null, adminToken))
                .getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("50.0000", adminView.get("hourlyRate").getAsString());
        assertEquals("100.00", adminView.get("cost").getAsString());
        JsonObject managerView = body(app.send("GET", milestonePath(milestoneId) + "/time", null, managerToken))
                .getAsJsonArray().get(0).getAsJsonObject();
        assertFalse(managerView.has("cost") || managerView.has("hourlyRate"));
        assertEquals(1, body(send("GET", milestonePath(milestoneId) + "/time", null)).getAsJsonArray().size());
    }

    @Test
    void manualHoursAreValidated() {
        assertEquals("hours must be more than 0 and at most 24",
                error(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 0}")));
        assertEquals(400, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 24.5}")));
        assertEquals(400, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": \"two\"}")));
        assertEquals(400, status(send("POST", milestonePath(milestoneId) + "/time", "{}")));
        assertEquals("You can only log time on milestones you are assigned to",
                error(send("POST", milestonePath(teammateMilestoneId) + "/time", "{\"hours\": 1}")));

        assertEquals(200, status(app.send("PUT", milestonePath(milestoneId) + "/status",
                "{\"status\": \"COMPLETE\"}", adminToken)));
        assertEquals(409, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 1}")));
        assertEquals(0.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
    }

    @Test
    void aTimerMeasuresTheTimeAndOnlyOneCanRun() {
        Map<String, Object> started = send("POST", milestonePath(milestoneId) + "/timer", null);
        assertEquals(201, status(started));
        assertFalse(body(started).getAsJsonObject().has("endedAt"));
        assertEquals(milestoneId, body(send("GET", "/timer", null)).getAsJsonObject().get("milestoneId").getAsString());

        // One milestone at a time: no second timer, and no hours by hand while it runs.
        assertEquals("A timer is already running; stop it before starting another",
                error(send("POST", milestonePath(milestoneId) + "/timer", null)));
        assertEquals(409, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 1}")));

        app.clock.advance(Duration.ofMinutes(90));
        // The access token lasts 15 minutes; a client refreshes it while the timer runs. Signing in again does too.
        employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        adminToken = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
        Map<String, Object> stopped = send("POST", "/timer/stop", null);
        assertEquals(200, status(stopped));
        assertEquals("1.50", body(stopped).getAsJsonObject().get("hours").getAsString());
        assertEquals(1.5, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        assertEquals("75.00", body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .get("spent").getAsString());

        assertEquals("No timer is running", error(send("POST", "/timer/stop", null)));
        assertEquals(404, status(send("GET", "/timer", null)));
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/timer", "{\"note\": \"Round two\"}")));
    }

    @Test
    void aTimerStopsCountingAtEightHours() {
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/timer", null)));
        app.clock.advance(Duration.ofHours(9));
        employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        adminToken = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();

        Map<String, Object> stopped = send("POST", "/timer/stop", null);
        assertEquals(200, status(stopped));
        JsonObject entry = body(stopped).getAsJsonObject();
        assertEquals("8.00", entry.get("hours").getAsString());
        assertEquals("2026-10-14T20:00:00Z", entry.get("endedAt").getAsString());
        assertEquals(8.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
    }

    @Test
    void aForgottenTimerIsClosedAtEightHoursSoANewOneCanStart() {
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/timer", null)));
        app.clock.advance(Duration.ofHours(30));
        employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        adminToken = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();

        // Looking at the timer closes it at the cap; the time is logged and nothing is running any more.
        assertEquals("No timer is running", error(send("GET", "/timer", null)));
        assertEquals(8.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/timer", null)));

        // Starting a timer or logging by hand also closes an expired one first.
        app.clock.advance(Duration.ofHours(8));
        employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        adminToken = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 1}")));
        assertEquals(17.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        assertTrue(body(app.send("GET", "/audit", null, adminToken)).toString().contains("timer_capped"));
    }

    @Test
    void projectProgressIsTheSumOfItsMilestones() {
        String teammateToken = app.signIn("teammate@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(201, status(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 2.25}")));
        assertEquals(201, status(app.send("POST", milestonePath(teammateMilestoneId) + "/time", "{\"hours\": 1.5}",
                teammateToken)));

        assertEquals(3.75, body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .get("progressTime").getAsDouble());
        // Employees see the whole project's progress, though only their own milestones.
        assertEquals(3.75, body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .get("progressTime").getAsDouble());
        // A progressTime sent for the project itself is ignored.
        assertEquals(3.75, body(app.send("PUT", "/projects/" + projectId, """
                {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 999,
                 "manager": "%s", "budget": 5000, "status": "IN_PROGRESS"}""".formatted(managerId), adminToken))
                .getAsJsonObject().get("progressTime").getAsDouble());
    }

    @Test
    void adminsCorrectLoggedTime() {
        String entryId = body(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 5, \"note\": \"Typo\"}"))
                .getAsJsonObject().get("id").getAsString();

        Map<String, Object> corrected = app.send("PUT", "/time/" + entryId,
                "{\"hours\": 0.5, \"reason\": \"Logged 5 instead of 0.5\"}", adminToken);
        assertEquals(200, status(corrected));
        JsonObject entry = body(corrected).getAsJsonObject();
        assertEquals("0.50", entry.get("hours").getAsString());
        assertEquals("25.00", entry.get("cost").getAsString());
        assertEquals("Typo", entry.get("note").getAsString());
        assertEquals(TestApp.ADMIN_EMAIL, entry.get("correctedBy").getAsString());
        assertEquals("Logged 5 instead of 0.5", entry.get("correctionReason").getAsString());

        assertEquals(0.5, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        assertEquals("25.00", body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .get("spent").getAsString());
        JsonObject audit = body(app.send("GET", "/audit", null, adminToken)).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("time_corrected", audit.get("action").getAsString());
        assertEquals("5.00", audit.getAsJsonObject("details").get("previousHours").getAsString());

        // Zeroing an entry is how a mistaken one is voided; nothing is deleted.
        assertEquals(200, status(app.send("PUT", "/time/" + entryId, "{\"hours\": 0, \"reason\": \"Duplicate\"}",
                adminToken)));
        assertEquals(0.0, milestoneAsAdmin(milestoneId).get("progressTime").getAsDouble());
        assertEquals(1, body(app.send("GET", milestonePath(milestoneId) + "/time", null, adminToken))
                .getAsJsonArray().size());
    }

    @Test
    void correctionsAreValidatedAndAdminOnly() {
        String entryId = body(send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 1}"))
                .getAsJsonObject().get("id").getAsString();
        assertEquals("reason is required", error(app.send("PUT", "/time/" + entryId, "{\"hours\": 2}", adminToken)));
        assertEquals("hours must be between 0 and 24", error(app.send("PUT", "/time/" + entryId,
                "{\"hours\": -1, \"reason\": \"x\"}", adminToken)));
        assertEquals(404, status(app.send("PUT", "/time/missing", "{\"hours\": 1, \"reason\": \"x\"}", adminToken)));
        assertEquals(403, status(send("PUT", "/time/" + entryId, "{\"hours\": 9, \"reason\": \"x\"}")));
        assertEquals(403, status(app.send("PUT", "/time/" + entryId, "{\"hours\": 9, \"reason\": \"x\"}",
                managerToken)));

        String timerId = body(send("POST", milestonePath(milestoneId) + "/timer", null)).getAsJsonObject()
                .get("id").getAsString();
        assertEquals(409, status(app.send("PUT", "/time/" + timerId, "{\"hours\": 1, \"reason\": \"x\"}",
                adminToken)));
    }

    @Test
    void employeesWithoutASalaryLogTimeThatCostsNothing() {
        String teammateToken = app.signIn("teammate@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(201, status(app.send("POST", milestonePath(teammateMilestoneId) + "/time", "{\"hours\": 3}",
                teammateToken)));
        assertEquals(3.0, milestoneAsAdmin(teammateMilestoneId).get("progressTime").getAsDouble());
        assertEquals(0, body(app.send("GET", "/projects/" + projectId, null, adminToken)).getAsJsonObject()
                .get("spent").getAsBigDecimal().signum());
    }

    @Test
    void onlyEmployeesLogTimeAndEmployeesCannotChangeAnythingElse() {
        assertEquals(403, status(app.send("POST", milestonePath(milestoneId) + "/time", "{\"hours\": 1}", adminToken)));
        assertEquals(403, status(app.send("POST", milestonePath(milestoneId) + "/timer", null, managerToken)));

        List<String[]> denied = List.of(
                new String[] {"POST", "/projects"}, new String[] {"PUT", "/projects/" + projectId},
                new String[] {"DELETE", "/projects/" + projectId}, new String[] {"POST", "/projects/p/restore"},
                new String[] {"POST", "/projects/" + projectId + "/milestones"},
                new String[] {"PUT", milestonePath(milestoneId)}, new String[] {"DELETE", milestonePath(milestoneId)},
                new String[] {"GET", "/audit"}, new String[] {"POST", "/users"},
                new String[] {"PUT", "/users/" + employeeId}, new String[] {"DELETE", "/users/" + teammateId},
                new String[] {"POST", "/users/u/restore"}, new String[] {"PUT", "/users/" + employeeId + "/manager"},
                new String[] {"PUT", "/users/" + employeeId + "/password"});
        for (String[] route : denied) {
            Map<String, Object> response = send(route[0], route[1], "{}");
            assertEquals(403, status(response), route[0] + " " + route[1]);
            assertEquals("Access denied", error(response));
        }
    }
}
