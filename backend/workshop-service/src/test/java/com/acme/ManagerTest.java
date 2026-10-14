package com.acme;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Managers view and edit the projects they manage and their milestones, and assign their own team, but cannot
 * create, archive or restore projects, hand a project to someone else, or see other managers' work.
 */
class ManagerTest {
    private final TestApp app = new TestApp();
    private String adminToken;
    private String managerId;
    private String managerToken;
    private String employeeId;
    private String otherEmployeeId;
    private String projectId;
    private String otherProjectId;

    @BeforeEach
    void setUpTwoTeams() {
        adminToken = app.setUpAdmin();
        managerId = app.createUser(adminToken, "manager@example.com", "MANAGER", null);
        String otherManagerId = app.createUser(adminToken, "other-manager@example.com", "MANAGER", null);
        employeeId = app.createUser(adminToken, "employee@example.com", "EMPLOYEE", managerId);
        otherEmployeeId = app.createUser(adminToken, "other-employee@example.com", "EMPLOYEE", otherManagerId);
        projectId = createProject("Apollo", managerId);
        otherProjectId = createProject("Gemini", otherManagerId);
        managerToken = app.signIn("manager@example.com", PASSWORD).get("accessToken").getAsString();
    }

    private String createProject(String name, String manager) {
        Map<String, Object> response = app.send("POST", "/projects", project(name, manager, "TODO"), adminToken);
        assertEquals(201, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonObject().get("id").getAsString();
    }

    private static String project(String name, String manager, String status) {
        return """
                {"name": "%s", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 0,
                 "manager": "%s", "budget": 1000, "status": "%s"}""".formatted(name, manager, status);
    }

    private static String milestone(String status, String... employees) {
        String assigned = String.join("\", \"", employees);
        return """
                {"name": "Design", "description": "Wireframes for the main flows", "startDate": "2026-01-01",
                 "endDate": "2026-02-01", "progressTime": 4, "assignedEmployees": [%s], "status": "%s"}"""
                .formatted(employees.length == 0 ? "" : "\"" + assigned + "\"", status);
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, managerToken);
    }

    @Test
    void managersSeeOnlyTheProjectsTheyManage() {
        assertEquals(List.of(projectId), body(send("GET", "/projects", null)).getAsJsonArray().asList().stream()
                .map(project -> project.getAsJsonObject().get("id").getAsString()).toList());
        assertEquals(200, status(send("GET", "/projects/" + projectId, null)));
        assertEquals("Access denied", error(send("GET", "/projects/" + otherProjectId, null)));
        assertEquals(403, status(send("GET", "/projects?archived=true", null)));
        assertEquals(404, status(send("GET", "/projects/missing", null)));
    }

    @Test
    void managersEditTheirProjectsButCannotHandThemOver() {
        Map<String, Object> updated = send("PUT", "/projects/" + projectId,
                project("Apollo (renamed)", managerId, "IN_PROGRESS"));
        assertEquals(200, status(updated));
        assertEquals("IN_PROGRESS", body(updated).getAsJsonObject().get("status").getAsString());

        assertEquals("Only admins can change a project's manager", error(send("PUT", "/projects/" + projectId,
                project("Apollo", app.users.findAll(false).stream()
                        .filter(user -> user.email().equals("other-manager@example.com")).findFirst().orElseThrow()
                        .id(), "TODO"))));
        assertEquals(403, status(send("PUT", "/projects/" + otherProjectId, project("Gemini", managerId, "TODO"))));

        JsonObject audit = body(app.send("GET", "/audit", null, adminToken)).getAsJsonArray().asList().stream()
                .map(entry -> entry.getAsJsonObject())
                .filter(entry -> entry.get("action").getAsString().equals("project_updated"))
                .findFirst().orElseThrow();
        assertEquals("manager@example.com", audit.get("actor").getAsString());
    }

    @Test
    void onlyAdminsChangeAProjectsDescription() {
        String described = project("Apollo", managerId, "TODO")
                .replaceFirst("\\{", "{\"description\": \"Crewed lunar landing\", ");
        assertEquals(200, status(app.send("PUT", "/projects/" + projectId, described, adminToken)));

        // A manager's update may leave the description out or repeat it; either way it is kept.
        Map<String, Object> withoutIt = send("PUT", "/projects/" + projectId,
                project("Apollo", managerId, "IN_PROGRESS"));
        assertEquals(200, status(withoutIt));
        assertEquals("Crewed lunar landing", body(withoutIt).getAsJsonObject().get("description").getAsString());
        assertEquals(200, status(send("PUT", "/projects/" + projectId, described)));

        assertEquals("Only admins can change a project's description", error(send("PUT", "/projects/" + projectId,
                described.replace("Crewed lunar landing", "Something else"))));
        assertEquals("Crewed lunar landing", body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .get("description").getAsString());

        // An admin's PUT replaces the project, so leaving the description out clears it.
        Map<String, Object> cleared = app.send("PUT", "/projects/" + projectId, project("Apollo", managerId, "TODO"),
                adminToken);
        assertFalse(body(cleared).getAsJsonObject().has("description"));
    }

    @Test
    void managersCannotCreateArchiveOrRestoreProjects() {
        assertEquals("Access denied", error(send("POST", "/projects", project("Mercury", managerId, "TODO"))));
        assertEquals(403, status(send("DELETE", "/projects/" + projectId, null)));
        assertEquals(204, status(app.send("DELETE", "/projects/" + projectId, null, adminToken)));
        assertEquals(403, status(send("POST", "/projects/" + projectId + "/restore", null)));
        // An archived project disappears from its manager's view.
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
        assertEquals(404, status(send("GET", "/projects/" + projectId, null)));
    }

    @Test
    void managersRunMilestonesAndAssignTheirOwnTeam() {
        Map<String, Object> added = send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", employeeId));
        assertEquals(201, status(added));
        JsonObject milestone = body(added).getAsJsonObject();
        String milestoneId = milestone.get("id").getAsString();
        assertEquals("Wireframes for the main flows", milestone.get("description").getAsString());

        Map<String, Object> completed = send("PUT", "/projects/" + projectId + "/milestones/" + milestoneId,
                milestone("COMPLETE", employeeId).replace("2026-02-01", "2026-03-01"));
        assertEquals(200, status(completed));
        assertEquals("COMPLETE", body(completed).getAsJsonObject().get("status").getAsString());
        assertEquals("2026-03-01", body(completed).getAsJsonObject().get("endDate").getAsString());

        assertEquals("assignedEmployees must be on the project manager's team: " + otherEmployeeId,
                error(send("PUT", "/projects/" + projectId + "/milestones/" + milestoneId,
                        milestone("COMPLETE", employeeId, otherEmployeeId))));
        assertEquals(400, status(send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", otherEmployeeId))));
        // Replacing milestones through the project update follows the same rule.
        assertEquals(400, status(send("PUT", "/projects/" + projectId, project("Apollo", managerId, "TODO")
                .replaceFirst("\\{", "{\"milestones\": [" + milestone("TODO", otherEmployeeId) + "], "))));

        assertEquals(403, status(send("POST", "/projects/" + otherProjectId + "/milestones", milestone("TODO"))));
        assertEquals(204, status(send("DELETE", "/projects/" + projectId + "/milestones/" + milestoneId, null)));
        assertTrue(body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .getAsJsonArray("milestones").isEmpty());
    }

    @Test
    void anEmployeeMovedAwayStaysOnTheirMilestonesButCannotBeNewlyAssigned() {
        String milestoneId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("TODO", employeeId)))
                .getAsJsonObject().get("id").getAsString();
        assertEquals(200, status(app.send("PUT", "/users/" + employeeId + "/manager", "{}", adminToken)));

        assertEquals(200, status(send("PUT", "/projects/" + projectId + "/milestones/" + milestoneId,
                milestone("IN_PROGRESS", employeeId))));
        assertEquals(400, status(send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", employeeId))));
    }

    /** The team rule binds admins too; moving the employee to the project's manager is how they switch teams. */
    @Test
    void adminsAlsoAssignOnlyTheProjectManagersTeam() {
        Map<String, Object> refused = app.send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", otherEmployeeId), adminToken);
        assertEquals(400, status(refused));
        assertEquals("assignedEmployees must be on the project manager's team: " + otherEmployeeId, error(refused));
        assertEquals(400, status(app.send("POST", "/projects", project("Mercury", managerId, "TODO")
                .replaceFirst("\\{", "{\"milestones\": [" + milestone("TODO", otherEmployeeId) + "], "),
                adminToken)));

        assertEquals(200, status(app.send("PUT", "/users/" + otherEmployeeId + "/manager",
                "{\"managerId\": \"" + managerId + "\"}", adminToken)));
        assertEquals(201, status(app.send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", otherEmployeeId), adminToken)));
    }

    @Test
    void managersAssignUnassignAndMoveTheirTeamBetweenMilestones() {
        String designId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("TODO")))
                .getAsJsonObject().get("id").getAsString();
        String buildId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("TODO")
                .replace("Design", "Build"))).getAsJsonObject().get("id").getAsString();
        String design = "/projects/" + projectId + "/milestones/" + designId;

        Map<String, Object> assigned = send("POST", design + "/assignees", "{\"employeeId\": \"" + employeeId + "\"}");
        assertEquals(200, status(assigned));
        assertEquals(List.of(employeeId), assignees(designId));
        assertEquals(409, status(send("POST", design + "/assignees", "{\"employeeId\": \"" + employeeId + "\"}")));
        assertEquals("assignedEmployees must be on the project manager's team: " + otherEmployeeId,
                error(send("POST", design + "/assignees", "{\"employeeId\": \"" + otherEmployeeId + "\"}")));

        Map<String, Object> moved = send("POST", design + "/assignees/" + employeeId + "/move",
                "{\"toMilestoneId\": \"" + buildId + "\"}");
        assertEquals(200, status(moved));
        assertEquals(List.of(), assignees(designId));
        assertEquals(List.of(employeeId), assignees(buildId));
        assertEquals(404, status(send("POST", design + "/assignees/" + employeeId + "/move",
                "{\"toMilestoneId\": \"" + buildId + "\"}")));

        JsonObject audit = body(app.send("GET", "/audit?action=employee_reassigned", null, adminToken)).getAsJsonArray()
                .get(0).getAsJsonObject().getAsJsonObject("details");
        assertEquals("Design", audit.get("fromMilestoneName").getAsString());
        assertEquals("Build", audit.get("milestoneName").getAsString());
        assertEquals("employee@example.com", audit.get("employeeName").getAsString());

        String build = "/projects/" + projectId + "/milestones/" + buildId;
        assertEquals(204, status(send("DELETE", build + "/assignees/" + employeeId, null)));
        assertEquals(List.of(), assignees(buildId));
        assertEquals(404, status(send("DELETE", build + "/assignees/" + employeeId, null)));
        // Only their own projects.
        assertEquals(403, status(send("POST", "/projects/" + otherProjectId + "/milestones/x/assignees",
                "{\"employeeId\": \"" + employeeId + "\"}")));
    }

    @Test
    void anEmployeeWorksOnOneUnfinishedMilestonePerProject() {
        String designId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("IN_PROGRESS",
                employeeId))).getAsJsonObject().get("id").getAsString();
        String buildId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("TODO")
                .replace("Design", "Build"))).getAsJsonObject().get("id").getAsString();
        String build = "/projects/" + projectId + "/milestones/" + buildId;

        Map<String, Object> second = send("POST", build + "/assignees", "{\"employeeId\": \"" + employeeId + "\"}");
        assertEquals(409, status(second));
        assertTrue(error(second).startsWith("employee@example.com is already working on 'Design' in this project"));
        assertEquals(409, status(send("POST", "/projects/" + projectId + "/milestones",
                milestone("TODO", employeeId).replace("Design", "Test"))));
        assertEquals(409, status(send("PUT", build, milestone("TODO", employeeId).replace("Design", "Build"))));

        // Moving is how they switch, and other projects are fine.
        assertEquals(200, status(send("POST", "/projects/" + projectId + "/milestones/" + designId + "/assignees/"
                + employeeId + "/move", "{\"toMilestoneId\": \"" + buildId + "\"}")));
        String apollo2 = body(app.send("POST", "/projects", project("Apollo 2", managerId, "TODO"), adminToken))
                .getAsJsonObject().get("id").getAsString();
        assertEquals(201, status(send("POST", "/projects/" + apollo2 + "/milestones", milestone("TODO", employeeId))));

        // A completed milestone does not count, but reopening it puts its people back to work.
        assertEquals(200, status(send("PUT", "/projects/" + projectId + "/milestones/" + buildId + "/status",
                "{\"status\": \"COMPLETE\"}")));
        assertEquals(200, status(send("POST", "/projects/" + projectId + "/milestones/" + designId + "/assignees",
                "{\"employeeId\": \"" + employeeId + "\"}")));
        assertEquals(409, status(send("PUT", "/projects/" + projectId + "/milestones/" + buildId + "/status",
                "{\"status\": \"IN_PROGRESS\"}")));
    }

    @Test
    void changingAssigneesLeavesLoggedProgressAlone() {
        String designId = body(send("POST", "/projects/" + projectId + "/milestones", milestone("IN_PROGRESS",
                employeeId))).getAsJsonObject().get("id").getAsString();
        String employeeToken = app.signIn("employee@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(201, status(app.send("POST", "/projects/" + projectId + "/milestones/" + designId + "/time",
                "{\"hours\": 3}", employeeToken)));

        String extraId = app.createUser(adminToken, "extra@example.com", "EMPLOYEE", managerId);
        assertEquals(200, status(send("POST", "/projects/" + projectId + "/milestones/" + designId + "/assignees",
                "{\"employeeId\": \"" + extraId + "\"}")));
        JsonObject stored = body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .getAsJsonArray("milestones").get(0).getAsJsonObject();
        assertEquals(7.0, stored.get("progressTime").getAsDouble());
    }

    private List<String> assignees(String milestoneId) {
        return body(send("GET", "/projects/" + projectId, null)).getAsJsonObject().getAsJsonArray("milestones")
                .asList().stream().map(element -> element.getAsJsonObject())
                .filter(found -> found.get("id").getAsString().equals(milestoneId)).findFirst().orElseThrow()
                .getAsJsonArray("assignedEmployees").asList().stream().map(element -> element.getAsString())
                .toList();
    }

    @Test
    void managersSeeTheNameAndStatusOfDependenciesOwnedByOtherManagers() {
        assertEquals(200, status(app.send("PUT", "/projects/" + projectId, project("Apollo", managerId, "TODO")
                .replaceFirst("\\{", "{\"dependsOn\": [\"" + otherProjectId + "\"], "), adminToken)));
        assertEquals(403, status(send("GET", "/projects/" + otherProjectId, null)));

        JsonObject dependency = body(send("GET", "/projects/" + projectId, null)).getAsJsonObject()
                .getAsJsonArray("dependencies").get(0).getAsJsonObject();
        assertEquals(otherProjectId, dependency.get("id").getAsString());
        assertEquals("Gemini", dependency.get("name").getAsString());
        assertEquals("TODO", dependency.get("status").getAsString());
        assertFalse(dependency.get("archived").getAsBoolean());
    }

    @Test
    void managersSeeTheirTeamWithoutSalaries() {
        assertEquals(201, status(app.send("POST", "/users", """
                {"email": "paid@example.com", "name": "Paid", "role": "EMPLOYEE", "managerId": "%s",
                 "jobType": "Developer", "salary": 70000, "password": "%s"}""".formatted(managerId, PASSWORD),
                adminToken)));

        List<JsonObject> team = body(send("GET", "/users", null)).getAsJsonArray().asList().stream()
                .map(user -> user.getAsJsonObject()).toList();
        assertEquals(List.of("employee@example.com", "paid@example.com"),
                team.stream().map(user -> user.get("email").getAsString()).sorted().toList());
        assertTrue(team.stream().noneMatch(user -> user.has("salary")));
        JsonObject paid = team.stream().filter(user -> user.get("email").getAsString().equals("paid@example.com"))
                .findFirst().orElseThrow();
        assertEquals("Developer", paid.get("jobType").getAsString());
        assertFalse(body(send("GET", "/users/" + paid.get("id").getAsString(), null)).getAsJsonObject().has("salary"));

        assertEquals(403, status(send("GET", "/users/" + otherEmployeeId, null)));
        assertEquals(403, status(send("GET", "/users/" + managerId, null)));
        assertEquals(403, status(send("GET", "/users?archived=true", null)));
    }

    @Test
    void managersCannotManageAccountsOrReadTheAuditLog() {
        List<String[]> denied = List.of(
                new String[] {"POST", "/users"}, new String[] {"PUT", "/users/" + employeeId},
                new String[] {"DELETE", "/users/" + employeeId}, new String[] {"POST", "/users/" + employeeId
                        + "/restore"}, new String[] {"PUT", "/users/" + employeeId + "/manager"},
                new String[] {"PUT", "/users/" + employeeId + "/password"}, new String[] {"GET", "/audit"});
        for (String[] route : denied) {
            Map<String, Object> response = send(route[0], route[1], "{}");
            assertEquals(403, status(response), route[0] + " " + route[1]);
            assertEquals("Access denied", error(response));
        }
    }
}
