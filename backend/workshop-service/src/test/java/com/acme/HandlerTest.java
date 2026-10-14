package com.acme;

import com.acme.repos.InMemoryProjectStore;
import com.acme.repos.StorageException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the project, milestone and audit routes as an admin, the way a function URL calls them. */
class HandlerTest {
    private TestApp app = new TestApp();
    private String token;
    private String managerId;
    private String employeeId;
    private String projectJson;
    private String milestoneJson;

    @BeforeEach
    void signInAsAdmin() {
        token = app.setUpAdmin();
        managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);
        projectJson = """
                {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 12.5,
                 "manager": "%s", "budget": 50000.75, "status": "IN_PROGRESS"}""".formatted(managerId);
        milestoneJson = """
                {"name": "Design", "startDate": "2026-01-01", "endDate": "2026-02-01", "progressTime": 4,
                 "assignedEmployees": ["%s"], "status": "TODO"}""".formatted(employeeId);
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    @Test
    void serviceRootAnswersWithoutSignIn() {
        Map<String, Object> response = app.send("GET", "/", null, null);
        assertEquals(200, status(response));
        assertEquals(Map.of("Content-Type", "application/json"), response.get("headers"));
        assertEquals("Hello from Spring Boot Lambda", body(response).getAsJsonObject().get("message").getAsString());
        // A direct invocation without an HTTP event is treated as GET /.
        assertEquals(200, status(app.handler.handleRequest(null, null)));
    }

    @Test
    void projectAndMilestoneLifecycle() {
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());

        Map<String, Object> created = send("POST", "/projects", projectJson);
        assertEquals(201, status(created));
        String id = body(created).getAsJsonObject().get("id").getAsString();
        assertEquals(409, status(send("POST", "/projects", withId(projectJson, id))));

        Map<String, Object> milestone = send("POST", "/projects/" + id + "/milestones", milestoneJson);
        assertEquals(201, status(milestone));
        String milestoneId = body(milestone).getAsJsonObject().get("id").getAsString();
        assertEquals(409, status(send("POST", "/projects/" + id + "/milestones", withId(milestoneJson, milestoneId))));

        // Updating the project without a milestones field keeps the milestone.
        assertEquals(200, status(send("PUT", "/projects/" + id, projectJson.replace("Apollo", "Gemini"))));
        JsonObject stored = body(send("GET", "/projects/" + id, null)).getAsJsonObject();
        assertEquals("Gemini", stored.get("name").getAsString());
        assertEquals("2026-12-31", stored.get("endDate").getAsString());
        assertEquals(50000.75, stored.get("budget").getAsDouble());
        assertEquals(managerId, stored.get("manager").getAsString());
        assertEquals(1, stored.getAsJsonArray("milestones").size());

        assertEquals(200, status(send("PUT", "/projects/" + id + "/milestones/" + milestoneId,
                milestoneJson.replace("TODO", "COMPLETE"))));
        JsonObject storedMilestone = body(send("GET", "/projects", null)).getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonArray("milestones").get(0).getAsJsonObject();
        assertEquals(milestoneId, storedMilestone.get("id").getAsString());
        assertEquals("COMPLETE", storedMilestone.get("status").getAsString());
        assertEquals(employeeId, storedMilestone.getAsJsonArray("assignedEmployees").get(0).getAsString());

        // An update that names milestones replaces them.
        assertEquals(0, body(send("PUT", "/projects/" + id, projectJson.replaceFirst("\\{", "{\"milestones\": [], ")))
                .getAsJsonObject().getAsJsonArray("milestones").size());
        assertEquals(201, status(send("POST", "/projects/" + id + "/milestones", milestoneJson)));
        String secondMilestoneId = body(send("GET", "/projects/" + id, null)).getAsJsonObject()
                .getAsJsonArray("milestones").get(0).getAsJsonObject().get("id").getAsString();

        Map<String, Object> archivedMilestone = send("DELETE", "/projects/" + id + "/milestones/" + secondMilestoneId,
                null);
        assertEquals(204, status(archivedMilestone));
        assertNull(archivedMilestone.get("body"));
        assertEquals(404, status(send("DELETE", "/projects/" + id + "/milestones/" + secondMilestoneId, null)));
        assertEquals(404, status(send("PUT", "/projects/" + id + "/milestones/" + secondMilestoneId, milestoneJson)));

        // DELETE archives the project: it leaves the active views but can still be listed and restored.
        assertEquals(204, status(send("DELETE", "/projects/" + id, null)));
        assertEquals(404, status(send("GET", "/projects/" + id, null)));
        assertEquals(404, status(send("DELETE", "/projects/" + id, null)));
        assertEquals(404, status(send("PUT", "/projects/" + id, projectJson)));
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
        JsonArray archivedProjects = body(send("GET", "/projects?archived=true", null)).getAsJsonArray();
        assertEquals(id, archivedProjects.get(0).getAsJsonObject().get("id").getAsString());
        // The id stays taken while the project is archived.
        assertEquals(409, status(send("POST", "/projects", withId(projectJson, id))));

        Map<String, Object> restored = send("POST", "/projects/" + id + "/restore", null);
        assertEquals(200, status(restored));
        // The project comes back as it was archived, including the name set by the last update.
        assertEquals("Apollo", body(restored).getAsJsonObject().get("name").getAsString());
        assertEquals(200, status(send("GET", "/projects/" + id, null)));
        assertEquals(0, body(send("GET", "/projects?archived=true", null)).getAsJsonArray().size());
        assertEquals(404, status(send("POST", "/projects/" + id + "/restore", null)));
        assertEquals(400, status(send("GET", "/projects?archived=maybe", null)));

        // Every project change was audited with the admin as actor, newest first; reads and rejections were not.
        List<JsonObject> projectEntries = body(send("GET", "/audit", null)).getAsJsonArray().asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(entry -> entry.getAsJsonObject("details").has("projectId"))
                .toList();
        assertEquals(List.of("project_restored", "project_archived", "milestone_archived", "milestone_added",
                "project_updated", "milestone_updated", "project_updated", "milestone_added", "project_created"),
                projectEntries.stream().map(entry -> entry.get("action").getAsString()).toList());
        JsonObject createdDetails = projectEntries.get(8).getAsJsonObject("details");
        assertEquals(id, createdDetails.get("projectId").getAsString());
        assertEquals("Apollo", createdDetails.get("projectName").getAsString());
        assertEquals(milestoneId, projectEntries.get(7).getAsJsonObject("details").get("milestoneId").getAsString());
        assertEquals(TestApp.ADMIN_EMAIL, projectEntries.get(0).get("actor").getAsString());
    }

    @Test
    void managersAndEmployeesMustBeActiveUsersInTheRightRole() {
        assertTrue(error(send("POST", "/projects", projectJson.replace(managerId, employeeId)))
                .startsWith("manager must reference an active manager"));
        assertTrue(error(send("POST", "/projects", projectJson.replace(managerId, "nobody")))
                .startsWith("manager must reference an active manager"));

        String id = body(send("POST", "/projects", projectJson)).getAsJsonObject().get("id").getAsString();
        assertTrue(error(send("POST", "/projects/" + id + "/milestones", milestoneJson.replace(employeeId, managerId)))
                .startsWith("assignedEmployees must reference an active employee"));
        String milestoneId = body(send("POST", "/projects/" + id + "/milestones", milestoneJson)).getAsJsonObject()
                .get("id").getAsString();

        // Once assigned, an employee who is later archived stays on the milestone and edits still work,
        // but they cannot be newly assigned anywhere else.
        assertEquals(204, status(send("DELETE", "/users/" + employeeId, null)));
        assertEquals(200, status(send("PUT", "/projects/" + id + "/milestones/" + milestoneId,
                milestoneJson.replace("TODO", "COMPLETE"))));
        assertEquals(400, status(send("POST", "/projects/" + id + "/milestones", milestoneJson)));
    }

    @Test
    void requestsThroughCloudFrontKeepTheirApiPrefix() {
        assertEquals(201, status(send("POST", "/api/workshop-service/projects", projectJson)));
        assertEquals(1, body(send("GET", "/api/workshop-service/projects/", null)).getAsJsonArray().size());
        assertEquals(200, status(app.send("GET", "/api/workshop-service", null, null)));
    }

    @Test
    void invalidRequestsAreRejected() {
        Map<String, Object> endBeforeStart = send("POST", "/projects", projectJson.replace("2026-12-31", "2025-01-01"));
        assertEquals(400, status(endBeforeStart));
        assertEquals("endDate must not be before startDate", error(endBeforeStart));

        assertTrue(error(send("POST", "/projects", projectJson.replace("IN_PROGRESS", "DONE")))
                .startsWith("status must be one of"));
        assertTrue(error(send("POST", "/projects", projectJson.replace("2026-12-31", "31.12.2026")))
                .startsWith("Dates must use the format"));
        assertEquals("name is required", error(send("POST", "/projects", projectJson.replace("Apollo", " "))));
        assertEquals("Request body is not valid JSON", error(send("POST", "/projects", "{ \"name\": ")));
        assertEquals("Request body must be a JSON object", error(send("POST", "/projects", "")));
        assertEquals("Request body must be a JSON object", error(send("POST", "/projects", "[]")));
        assertEquals("Request body has a field of the wrong type",
                error(send("POST", "/projects", projectJson.replace("12.5", "{}"))));
        assertEquals("Request body has a field of the wrong type",
                error(send("POST", "/projects", projectJson.replace("50000.75", "\"lots\""))));

        assertEquals(404, status(send("PUT", "/projects/missing", projectJson)));
        assertEquals(404, status(send("POST", "/projects/missing/milestones", milestoneJson)));
        assertEquals("No route for GET /unknown", error(send("GET", "/unknown", null)));
        assertEquals(404, status(send("PATCH", "/projects", projectJson)));
        assertEquals(404, status(send("POST", "/projects/x/tasks", milestoneJson)));

        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
    }

    @Test
    void base64BodiesAreDecoded() {
        Map<String, Object> event = new HashMap<>();
        event.put("rawPath", "/projects");
        event.put("requestContext", Map.of("http", Map.of("method", "POST")));
        event.put("headers", Map.of("Authorization", "Bearer " + token));
        event.put("body", java.util.Base64.getEncoder().encodeToString(projectJson.getBytes()));
        event.put("isBase64Encoded", true);
        assertEquals(201, status(app.handler.handleRequest(event, null)));
    }

    @Test
    void databaseFailuresBecomeServerErrors() {
        app = new TestApp(new InMemoryProjectStore() {
            @Override
            public List<com.acme.models.Project> findAll(boolean archived) {
                throw new StorageException("connection refused", null);
            }
        });
        token = app.setUpAdmin();

        Map<String, Object> response = send("GET", "/projects", null);
        assertEquals(500, status(response));
        assertEquals("Database error", error(response));
    }

    private static String withId(String json, String id) {
        return json.replaceFirst("\\{", "{\"id\": \"" + id + "\", ");
    }
}
