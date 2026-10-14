package com.acme;

import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.repos.InMemoryProjectRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Completion times, frozen dates, milestone dates inside the project, and milestone dependencies. */
class IntegrityTest {
    private final InMemoryProjectRepository repository = new InMemoryProjectRepository();
    private final TestApp app = new TestApp(repository);
    private String token;
    private String managerId;
    private String employeeId;
    /** Who milestones created by the helpers are assigned to; nobody unless a test sets it. */
    private String assignee;
    private String projectId;

    @BeforeEach
    void setUp() {
        token = app.setUpAdmin();
        managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);
        projectId = body(send("POST", "/projects", project("2026-01-01", "2026-12-31", "IN_PROGRESS")))
                .getAsJsonObject().get("id").getAsString();
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    private String project(String start, String end, String status) {
        return """
                {"name": "Apollo", "startDate": "%s", "endDate": "%s", "manager": "%s", "budget": 1000,
                 "status": "%s"}""".formatted(start, end, managerId, status);
    }

    private String milestone(String name, String start, String end, String status, String... dependsOn) {
        String dependencies = dependsOn.length == 0 ? "" : "\"" + String.join("\", \"", dependsOn) + "\"";
        return """
                {"name": "%s", "startDate": "%s", "endDate": "%s", "status": "%s", "assignedEmployees": [%s],
                 "dependsOn": [%s]}""".formatted(name, start, end, status,
                assignee == null ? "" : "\"" + assignee + "\"", dependencies);
    }

    private String add(String name, String status, String... dependsOn) {
        Map<String, Object> response = send("POST", milestones(),
                milestone(name, "2026-02-01", "2026-03-01", status, dependsOn));
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private String milestones() {
        return "/projects/" + projectId + "/milestones";
    }

    private Map<String, Object> setStatus(String milestoneId, String status) {
        return send("PUT", milestones() + "/" + milestoneId + "/status", "{\"status\": \"" + status + "\"}");
    }

    private JsonObject milestoneById(String milestoneId) {
        return body(send("GET", "/projects/" + projectId, null)).getAsJsonObject().getAsJsonArray("milestones")
                .asList().stream().map(JsonElement::getAsJsonObject)
                .filter(found -> found.get("id").getAsString().equals(milestoneId)).findFirst().orElseThrow();
    }

    @Test
    void completionTimesAreRecordedKeptAndClearedOnReopening() {
        String design = add("Design", "IN_PROGRESS");
        assertFalse(milestoneById(design).has("completedAt"));

        Map<String, Object> completed = setStatus(design, "COMPLETE");
        assertEquals("2026-10-14T12:00:00Z", body(completed).getAsJsonObject().get("completedAt").getAsString());

        // Later edits that keep it complete keep the original time; a completedAt sent by a client is ignored.
        app.clock.advance(Duration.ofHours(3));
        token = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
        assertEquals(200, status(send("PUT", milestones() + "/" + design, milestone("Design (renamed)", "2026-02-01",
                "2026-03-01", "COMPLETE").replaceFirst("\\{", "{\"completedAt\": \"2020-01-01T00:00:00Z\", "))));
        assertEquals("2026-10-14T12:00:00Z", milestoneById(design).get("completedAt").getAsString());

        assertEquals(200, status(setStatus(design, "IN_PROGRESS")));
        assertFalse(milestoneById(design).has("completedAt"));

        Map<String, Object> projectDone = send("PUT", "/projects/" + projectId,
                project("2026-01-01", "2026-12-31", "COMPLETE"));
        assertEquals("2026-10-14T15:00:00Z", body(projectDone).getAsJsonObject().get("completedAt").getAsString());
        assertFalse(body(send("PUT", "/projects/" + projectId, project("2026-01-01", "2026-12-31", "IN_PROGRESS")))
                .getAsJsonObject().has("completedAt"));
    }

    @Test
    void statusChangeTimesAreRecordedOnCreationAndEachStatusChange() {
        JsonObject created = body(send("GET", "/projects/" + projectId, null)).getAsJsonObject();
        assertEquals("2026-10-14T12:00:00Z", created.get("statusChangedAt").getAsString());
        String design = add("Design", "TODO");
        assertEquals("2026-10-14T12:00:00Z", milestoneById(design).get("statusChangedAt").getAsString());

        app.clock.advance(Duration.ofDays(3));
        token = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
        // Edits that keep the status keep the time.
        assertEquals(200, status(send("PUT", milestones() + "/" + design, milestone("Design (renamed)", "2026-02-01",
                "2026-03-01", "TODO"))));
        assertEquals("2026-10-14T12:00:00Z", milestoneById(design).get("statusChangedAt").getAsString());
        assertEquals(200, status(send("PUT", "/projects/" + projectId,
                project("2026-01-01", "2026-12-31", "IN_PROGRESS").replace("Apollo", "Apollo II"))));
        assertEquals("2026-10-14T12:00:00Z", body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .get("statusChangedAt").getAsString());

        // A status change restarts the clock.
        assertEquals(200, status(setStatus(design, "IN_PROGRESS")));
        assertEquals("2026-10-17T12:00:00Z", milestoneById(design).get("statusChangedAt").getAsString());
        assertEquals(200, status(send("PUT", "/projects/" + projectId, project("2026-01-01", "2026-12-31",
                "AWAITING_REVIEW"))));
        assertEquals("2026-10-17T12:00:00Z", body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .get("statusChangedAt").getAsString());
    }

    @Test
    void completedWorkHasFrozenDates() {
        String design = add("Design", "IN_PROGRESS");
        assertEquals(200, status(setStatus(design, "COMPLETE")));
        assertEquals("The dates of completed milestone 'Design' are frozen; reopen it to change them",
                error(send("PUT", milestones() + "/" + design, milestone("Design", "2026-02-01", "2026-04-01",
                        "COMPLETE"))));
        // Completing and changing dates in one request is allowed; the freeze starts once it is complete.
        String build = add("Build", "IN_PROGRESS");
        assertEquals(200, status(send("PUT", milestones() + "/" + build, milestone("Build", "2026-03-01",
                "2026-04-01", "COMPLETE"))));
        // Reopening unfreezes the dates, also within the same request.
        assertEquals(200, status(send("PUT", milestones() + "/" + design, milestone("Design", "2026-02-01",
                "2026-04-01", "IN_PROGRESS"))));

        assertEquals(200, status(send("PUT", "/projects/" + projectId,
                project("2026-01-01", "2026-12-31", "COMPLETE"))));
        assertEquals("The dates of a completed project are frozen; reopen it to change them",
                error(send("PUT", "/projects/" + projectId, project("2026-01-01", "2027-06-30", "COMPLETE"))));
        assertEquals(409, status(send("PUT", milestones() + "/" + design, milestone("Design", "2026-02-01",
                "2026-05-01", "IN_PROGRESS"))));
        assertEquals(200, status(send("PUT", "/projects/" + projectId, project("2026-01-01", "2027-06-30",
                "IN_PROGRESS"))));
    }

    @Test
    void milestoneDatesStayWithinTheProject() {
        assertEquals("Milestone 'Early' must fall within the project's dates (2026-01-01 to 2026-12-31)",
                error(send("POST", milestones(), milestone("Early", "2025-12-01", "2026-02-01", "TODO"))));
        assertEquals(400, status(send("POST", milestones(), milestone("Late", "2026-11-01", "2027-01-31", "TODO"))));
        String design = add("Design", "TODO");
        assertEquals(400, status(send("PUT", milestones() + "/" + design,
                milestone("Design", "2026-02-01", "2027-03-01", "TODO"))));

        // Shrinking the project so a milestone falls outside is refused too.
        assertEquals(400, status(send("PUT", "/projects/" + projectId, project("2026-01-01", "2026-02-15",
                "IN_PROGRESS"))));
        assertEquals(400, status(send("POST", "/projects", project("2026-01-01", "2026-06-30", "TODO")
                .replaceFirst("\\{", "{\"milestones\": [" + milestone("Outside", "2026-07-01", "2026-08-01", "TODO")
                        + "], "))));
    }

    @Test
    void ruleViolationsSavedBeforeTheRulesStayEditable() {
        Project stored = repository.findById(projectId).orElseThrow();
        Milestone outside = new Milestone("legacy", "Legacy", null, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 2, 1), 0, List.of(), null, null, null, null, null);
        assertTrue(repository.replace(stored.withMilestones(List.of(outside))));

        // Renaming the project does not touch the milestone's dates, so the old milestone is not re-checked.
        assertEquals(200, status(send("PUT", "/projects/" + projectId,
                project("2026-01-01", "2026-12-31", "IN_PROGRESS").replace("Apollo", "Apollo II"))));
        assertEquals(400, status(send("PUT", "/projects/" + projectId, project("2026-01-02", "2026-12-31",
                "IN_PROGRESS"))));
    }

    @Test
    void aMilestoneCannotStartOrCompleteBeforeItsDependencies() {
        String design = add("Design", "IN_PROGRESS");
        assignee = employeeId;
        String build = add("Build", "TODO", design);

        assertEquals("Milestone 'Build' cannot start or complete until the milestones it depends on are complete:"
                + " Design", error(setStatus(build, "IN_PROGRESS")));
        assertEquals(409, status(setStatus(build, "COMPLETE")));
        assertEquals(409, status(send("POST", milestones(), milestone("Test", "2026-02-01", "2026-03-01",
                "IN_PROGRESS", design))));

        // An employee can neither log time on it nor send it for review while it is blocked.
        String employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals("This milestone cannot be worked on until the milestones it depends on are complete: Design",
                error(app.send("POST", milestones() + "/" + build + "/time", "{\"hours\": 1}", employeeToken)));
        assertEquals(409, status(app.send("POST", milestones() + "/" + build + "/timer", null, employeeToken)));
        assertEquals(409, status(app.send("PUT", milestones() + "/" + build + "/status",
                "{\"status\": \"AWAITING_REVIEW\"}", employeeToken)));

        assertEquals(200, status(setStatus(design, "COMPLETE")));
        assertEquals(200, status(setStatus(build, "IN_PROGRESS")));
        assertEquals(201, status(app.send("POST", milestones() + "/" + build + "/time", "{\"hours\": 1}",
                employeeToken)));

        assertEquals("Milestone 'Design' cannot be reopened while milestones that depend on it have started: Build",
                error(setStatus(design, "IN_PROGRESS")));
        assertEquals(200, status(setStatus(build, "TODO")));
        assertEquals(200, status(setStatus(design, "IN_PROGRESS")));
    }

    @Test
    void dependenciesMustBeValidAndKeepTheirTargets() {
        String design = add("Design", "TODO");
        String build = add("Build", "TODO", design);

        assertTrue(error(send("POST", milestones(), milestone("Test", "2026-02-01", "2026-03-01", "TODO",
                "nowhere"))).startsWith("Milestone 'Test' depends on a milestone that is not part of this project"));
        assertEquals("Milestone 'Build' cannot depend on itself", error(send("PUT", milestones() + "/" + build,
                milestone("Build", "2026-02-01", "2026-03-01", "TODO", build))));
        assertEquals("Milestone dependencies cannot form a cycle: Design → Build → Design",
                error(send("PUT", milestones() + "/" + design, milestone("Design", "2026-02-01", "2026-03-01",
                        "TODO", build))));

        assertEquals("Other milestones depend on 'Design'; remove those dependencies first: Build",
                error(send("DELETE", milestones() + "/" + design, null)));
        assertEquals(200, status(send("PUT", milestones() + "/" + build, milestone("Build", "2026-02-01",
                "2026-03-01", "TODO"))));
        assertEquals(204, status(send("DELETE", milestones() + "/" + design, null)));

        JsonObject stored = milestoneById(build);
        assertTrue(stored.getAsJsonArray("dependsOn").isEmpty());
    }
}
