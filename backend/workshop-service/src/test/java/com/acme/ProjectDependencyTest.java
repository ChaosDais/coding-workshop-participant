package com.acme;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projects that depend on other projects: who may set them, what they block, and what keeps the chain valid. */
class ProjectDependencyTest {
    private final TestApp app = new TestApp();
    private String token;
    private String managerId;
    private String employeeId;
    private String designId;
    private String buildId;

    @BeforeEach
    void setUp() {
        token = app.setUpAdmin();
        managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);
        designId = create("Design programme", "IN_PROGRESS");
        buildId = create("Build programme", "TODO", designId);
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    /** "Design..." projects run first; the others start once they end, as projects depending on them must. */
    private static String dates(String name) {
        return name.startsWith("Design") ? "\"startDate\": \"2028-01-01\", \"endDate\": \"2028-06-30\""
                : "\"startDate\": \"2028-07-01\", \"endDate\": \"2028-12-31\"";
    }

    private String project(String name, String status, String... dependsOn) {
        String dependencies = dependsOn.length == 0 ? "" : "\"" + String.join("\", \"", dependsOn) + "\"";
        return """
                {"name": "%s", %s, "manager": "%s", "budget": 1000,
                 "status": "%s", "dependsOn": [%s]}""".formatted(name, dates(name), managerId, status, dependencies);
    }

    /** A project body without a dependsOn field, which keeps the existing dependencies on update. */
    private String projectWithoutDependencies(String name, String status) {
        return """
                {"name": "%s", %s, "manager": "%s", "budget": 1000,
                 "status": "%s"}""".formatted(name, dates(name), managerId, status);
    }

    private String create(String name, String status, String... dependsOn) {
        Map<String, Object> response = send("POST", "/projects", project(name, status, dependsOn));
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private String milestone(String status) {
        return """
                {"name": "Foundations", "startDate": "2028-08-01", "endDate": "2028-09-01", "status": "%s",
                 "assignedEmployees": ["%s"]}""".formatted(status, employeeId);
    }

    @Test
    void aProjectCannotStartUntilItsDependenciesAreComplete() {
        assertEquals("Project 'Build programme' cannot start or complete until the projects it depends on are"
                + " complete: Design programme", error(send("PUT", "/projects/" + buildId,
                projectWithoutDependencies("Build programme", "IN_PROGRESS"))));
        assertEquals(409, status(send("PUT", "/projects/" + buildId,
                projectWithoutDependencies("Build programme", "COMPLETE"))));
        assertEquals(409, status(send("POST", "/projects", project("Launch", "IN_PROGRESS", designId))));

        assertEquals(200, status(send("PUT", "/projects/" + designId,
                projectWithoutDependencies("Design programme", "COMPLETE"))));
        assertEquals(200, status(send("PUT", "/projects/" + buildId,
                projectWithoutDependencies("Build programme", "IN_PROGRESS"))));
    }

    @Test
    void itsMilestonesCannotStartAndNoTimeCanBeLoggedWhileItIsBlocked() {
        assertEquals("Milestones of project 'Build programme' cannot start until the projects it depends on are"
                + " complete: Design programme",
                error(send("POST", "/projects/" + buildId + "/milestones", milestone("IN_PROGRESS"))));
        Map<String, Object> planned = send("POST", "/projects/" + buildId + "/milestones", milestone("TODO"));
        assertEquals(201, status(planned));
        String milestoneId = body(planned).getAsJsonObject().get("id").getAsString();
        String milestonePath = "/projects/" + buildId + "/milestones/" + milestoneId;
        assertEquals(409, status(send("PUT", milestonePath + "/status", "{\"status\": \"IN_PROGRESS\"}")));

        String employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals("This project cannot be worked on until the projects it depends on are complete: Design"
                + " programme", error(app.send("POST", milestonePath + "/time", "{\"hours\": 1}", employeeToken)));
        assertEquals(409, status(app.send("POST", milestonePath + "/timer", null, employeeToken)));

        assertEquals(200, status(send("PUT", "/projects/" + designId,
                projectWithoutDependencies("Design programme", "COMPLETE"))));
        assertEquals(200, status(send("PUT", milestonePath + "/status", "{\"status\": \"IN_PROGRESS\"}")));
        assertEquals(201, status(app.send("POST", milestonePath + "/time", "{\"hours\": 1}", employeeToken)));
    }

    @Test
    void dependenciesMustPointAtOtherActiveProjectsWithoutCycles() {
        assertEquals("Project dependencies cannot form a cycle: Design programme → Build programme → Design"
                + " programme", error(send("PUT", "/projects/" + designId,
                project("Design programme", "IN_PROGRESS", buildId))));
        assertEquals("Project 'Design programme' cannot depend on itself", error(send("PUT", "/projects/" + designId,
                project("Design programme", "IN_PROGRESS", designId))));
        assertTrue(error(send("POST", "/projects", project("Launch", "TODO", "nowhere")))
                .startsWith("Project 'Launch' depends on a project that does not exist or is archived"));

        String retiredId = create("Retired", "TODO");
        assertEquals(204, status(send("DELETE", "/projects/" + retiredId, null)));
        assertEquals(400, status(send("POST", "/projects", project("Launch", "TODO", retiredId))));
    }

    @Test
    void theChainStaysIntact() {
        assertEquals(200, status(send("PUT", "/projects/" + designId,
                projectWithoutDependencies("Design programme", "COMPLETE"))));
        assertEquals(200, status(send("PUT", "/projects/" + buildId,
                projectWithoutDependencies("Build programme", "IN_PROGRESS"))));

        assertEquals("Project 'Design programme' cannot be reopened while projects that depend on it have started:"
                + " Build programme", error(send("PUT", "/projects/" + designId,
                projectWithoutDependencies("Design programme", "IN_PROGRESS"))));
        assertEquals("Other projects depend on 'Design programme'; remove those dependencies first: Build programme",
                error(send("DELETE", "/projects/" + designId, null)));

        // Removing the dependency frees both.
        assertEquals(200, status(send("PUT", "/projects/" + buildId, project("Build programme", "IN_PROGRESS"))));
        assertEquals(204, status(send("DELETE", "/projects/" + designId, null)));
    }

    @Test
    void onlyAdminsChangeDependenciesAndUpdatesWithoutThemKeepThem() {
        // An update that leaves dependsOn out keeps the dependencies.
        assertEquals(200, status(send("PUT", "/projects/" + buildId, projectWithoutDependencies("Renamed", "TODO"))));
        JsonArray kept = body(send("GET", "/projects/" + buildId, null)).getAsJsonObject().getAsJsonArray("dependsOn");
        assertEquals(designId, kept.get(0).getAsString());

        String managerToken = app.signIn("manager@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(200, status(app.send("PUT", "/projects/" + buildId,
                projectWithoutDependencies("Renamed again", "TODO"), managerToken)));
        assertEquals(200, status(app.send("PUT", "/projects/" + buildId,
                project("Renamed again", "TODO", designId), managerToken)));
        assertEquals("Only admins can change a project's dependencies", error(app.send("PUT", "/projects/" + buildId,
                project("Renamed again", "TODO"), managerToken)));
    }
}
