package com.acme;

import com.acme.audit.AuditService;
import com.acme.config.AppConfig;
import com.acme.projects.ProjectService;
import com.acme.repos.AuditStore;
import com.acme.repos.InMemoryAuditStore;
import com.acme.repos.InMemoryProjectStore;
import com.acme.repos.ProjectStore;
import com.acme.repos.StorageException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises every route through the Lambda entry point, the way a function URL calls it. */
class HandlerTest {
    private static final String PROJECT_JSON = """
            {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 12.5,
             "manager": "alice", "budget": 50000.75, "status": "IN_PROGRESS"}""";
    private static final String MILESTONE_JSON = """
            {"name": "Design", "startDate": "2026-01-01", "endDate": "2026-02-01", "progressTime": 4,
             "assignedEmployees": ["bob", "carol"], "status": "TODO"}""";

    private Handler handler = handler(new InMemoryProjectStore(), new InMemoryAuditStore());

    private static Handler handler(ProjectStore projects, AuditStore audit) {
        AuditService auditService = new AuditService(audit);
        return new Handler(new AppConfig().router(new ProjectService(projects, auditService), auditService));
    }

    /** Mimics the Lambda function URL event. */
    private Map<String, Object> send(String method, String path, String body) {
        Map<String, Object> event = new HashMap<>();
        event.put("rawPath", path);
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            Map<String, String> query = new HashMap<>();
            for (String pair : path.substring(queryStart + 1).split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
            event.put("queryStringParameters", query);
            path = path.substring(0, queryStart);
            event.put("rawPath", path);
        }
        event.put("requestContext", Map.of("http", Map.of("method", method, "path", path)));
        if (body != null) {
            event.put("body", body);
            event.put("isBase64Encoded", false);
        }
        return handler.handleRequest(event, null);
    }

    private static JsonElement body(Map<String, Object> response) {
        return new Gson().fromJson((String) response.get("body"), JsonElement.class);
    }

    private static String error(Map<String, Object> response) {
        return body(response).getAsJsonObject().get("error").getAsString();
    }

    @Test
    void serviceRootAnswers() {
        Map<String, Object> response = send("GET", "/", null);
        assertEquals(200, response.get("statusCode"));
        assertEquals(Map.of("Content-Type", "application/json"), response.get("headers"));
        assertEquals("Hello from Spring Boot Lambda", body(response).getAsJsonObject().get("message").getAsString());
        // A direct invocation without an HTTP event is treated as GET /.
        assertEquals(200, handler.handleRequest(null, null).get("statusCode"));
    }

    @Test
    void projectAndMilestoneLifecycle() {
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());

        Map<String, Object> created = send("POST", "/projects", PROJECT_JSON);
        assertEquals(201, created.get("statusCode"));
        String id = body(created).getAsJsonObject().get("id").getAsString();
        assertEquals(409, send("POST", "/projects",
                PROJECT_JSON.replaceFirst("\\{", "{\"id\": \"" + id + "\", ")).get("statusCode"));

        Map<String, Object> milestone = send("POST", "/projects/" + id + "/milestones", MILESTONE_JSON);
        assertEquals(201, milestone.get("statusCode"));
        String milestoneId = body(milestone).getAsJsonObject().get("id").getAsString();
        assertEquals(409, send("POST", "/projects/" + id + "/milestones",
                MILESTONE_JSON.replaceFirst("\\{", "{\"id\": \"" + milestoneId + "\", ")).get("statusCode"));

        // Updating the project without a milestones field keeps the milestone.
        Map<String, Object> updated = send("PUT", "/projects/" + id, PROJECT_JSON.replace("Apollo", "Gemini"));
        assertEquals(200, updated.get("statusCode"));
        JsonObject stored = body(send("GET", "/projects/" + id, null)).getAsJsonObject();
        assertEquals("Gemini", stored.get("name").getAsString());
        assertEquals("2026-12-31", stored.get("endDate").getAsString());
        assertEquals(50000.75, stored.get("budget").getAsDouble());
        assertEquals(1, stored.getAsJsonArray("milestones").size());

        Map<String, Object> milestoneUpdate = send("PUT", "/projects/" + id + "/milestones/" + milestoneId,
                MILESTONE_JSON.replace("TODO", "COMPLETE"));
        assertEquals(200, milestoneUpdate.get("statusCode"));
        JsonObject storedMilestone = body(send("GET", "/projects", null)).getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonArray("milestones").get(0).getAsJsonObject();
        assertEquals(milestoneId, storedMilestone.get("id").getAsString());
        assertEquals("COMPLETE", storedMilestone.get("status").getAsString());
        assertEquals(2, storedMilestone.getAsJsonArray("assignedEmployees").size());

        // An update that names milestones replaces them.
        assertEquals(0, body(send("PUT", "/projects/" + id, PROJECT_JSON.replaceFirst("\\{", "{\"milestones\": [], ")))
                .getAsJsonObject().getAsJsonArray("milestones").size());
        assertEquals(201, send("POST", "/projects/" + id + "/milestones", MILESTONE_JSON).get("statusCode"));
        String secondMilestoneId = body(send("GET", "/projects/" + id, null)).getAsJsonObject()
                .getAsJsonArray("milestones").get(0).getAsJsonObject().get("id").getAsString();

        Map<String, Object> archivedMilestone = send("DELETE", "/projects/" + id + "/milestones/" + secondMilestoneId,
                null);
        assertEquals(204, archivedMilestone.get("statusCode"));
        assertNull(archivedMilestone.get("body"));
        assertEquals(404, send("DELETE", "/projects/" + id + "/milestones/" + secondMilestoneId, null)
                .get("statusCode"));
        assertEquals(404, send("PUT", "/projects/" + id + "/milestones/" + secondMilestoneId, MILESTONE_JSON)
                .get("statusCode"));

        // DELETE archives the project: it leaves the active views but can still be listed and restored.
        assertEquals(204, send("DELETE", "/projects/" + id, null).get("statusCode"));
        assertEquals(404, send("GET", "/projects/" + id, null).get("statusCode"));
        assertEquals(404, send("DELETE", "/projects/" + id, null).get("statusCode"));
        assertEquals(404, send("PUT", "/projects/" + id, PROJECT_JSON).get("statusCode"));
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
        JsonArray archivedProjects = body(send("GET", "/projects?archived=true", null)).getAsJsonArray();
        assertEquals(id, archivedProjects.get(0).getAsJsonObject().get("id").getAsString());
        // The id stays taken while the project is archived.
        assertEquals(409, send("POST", "/projects",
                PROJECT_JSON.replaceFirst("\\{", "{\"id\": \"" + id + "\", ")).get("statusCode"));

        Map<String, Object> restored = send("POST", "/projects/" + id + "/restore", null);
        assertEquals(200, restored.get("statusCode"));
        // The project comes back as it was archived, including the name set by the last update.
        assertEquals("Apollo", body(restored).getAsJsonObject().get("name").getAsString());
        assertEquals(200, send("GET", "/projects/" + id, null).get("statusCode"));
        assertEquals(0, body(send("GET", "/projects?archived=true", null)).getAsJsonArray().size());
        assertEquals(404, send("POST", "/projects/" + id + "/restore", null).get("statusCode"));
        assertEquals(400, send("GET", "/projects?archived=maybe", null).get("statusCode"));

        // Every change was audited, newest first; reads and rejected requests were not.
        JsonArray audit = body(send("GET", "/audit", null)).getAsJsonArray();
        List<String> actions = audit.asList().stream()
                .map(entry -> entry.getAsJsonObject().get("action").getAsString()).toList();
        assertEquals(List.of("project_restored", "project_archived", "milestone_archived", "milestone_added",
                "project_updated", "milestone_updated", "project_updated", "milestone_added", "project_created"),
                actions);
        JsonObject createdDetails = audit.get(8).getAsJsonObject().getAsJsonObject("details");
        assertEquals(id, createdDetails.get("projectId").getAsString());
        assertEquals("Apollo", createdDetails.get("projectName").getAsString());
        assertEquals(milestoneId, audit.get(7).getAsJsonObject().getAsJsonObject("details")
                .get("milestoneId").getAsString());
        assertEquals("system", audit.get(0).getAsJsonObject().get("actor").getAsString());
    }

    @Test
    void requestsThroughCloudFrontKeepTheirApiPrefix() {
        assertEquals(201, send("POST", "/api/workshop-service/projects", PROJECT_JSON).get("statusCode"));
        assertEquals(1, body(send("GET", "/api/workshop-service/projects/", null)).getAsJsonArray().size());
        assertEquals(200, send("GET", "/api/workshop-service", null).get("statusCode"));
    }

    @Test
    void invalidRequestsAreRejected() {
        Map<String, Object> endBeforeStart = send("POST", "/projects", PROJECT_JSON.replace("2026-12-31", "2025-01-01"));
        assertEquals(400, endBeforeStart.get("statusCode"));
        assertEquals("endDate must not be before startDate", error(endBeforeStart));

        Map<String, Object> unknownStatus = send("POST", "/projects", PROJECT_JSON.replace("IN_PROGRESS", "DONE"));
        assertEquals(400, unknownStatus.get("statusCode"));
        assertTrue(error(unknownStatus).startsWith("status must be one of"));

        Map<String, Object> badDate = send("POST", "/projects", PROJECT_JSON.replace("2026-12-31", "31.12.2026"));
        assertEquals(400, badDate.get("statusCode"));
        assertTrue(error(badDate).startsWith("Dates must use the format"));

        assertEquals("name is required", error(send("POST", "/projects", PROJECT_JSON.replace("Apollo", " "))));
        assertEquals("Request body is not valid JSON", error(send("POST", "/projects", "{ \"name\": ")));
        assertEquals("Request body must be a JSON object", error(send("POST", "/projects", "")));
        assertEquals("Request body must be a JSON object", error(send("POST", "/projects", "[]")));
        assertEquals("Request body has a field of the wrong type",
                error(send("POST", "/projects", PROJECT_JSON.replace("12.5", "{}"))));

        assertEquals(404, send("PUT", "/projects/missing", PROJECT_JSON).get("statusCode"));
        assertEquals(404, send("POST", "/projects/missing/milestones", MILESTONE_JSON).get("statusCode"));
        assertEquals("No route for GET /unknown", error(send("GET", "/unknown", null)));
        assertEquals(404, send("PATCH", "/projects", PROJECT_JSON).get("statusCode"));
        assertEquals(404, send("POST", "/projects/x/tasks", MILESTONE_JSON).get("statusCode"));

        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
        assertEquals(0, body(send("GET", "/audit", null)).getAsJsonArray().size());
    }

    @Test
    void base64BodiesAreDecoded() {
        Map<String, Object> event = new HashMap<>();
        event.put("rawPath", "/projects");
        event.put("requestContext", Map.of("http", Map.of("method", "POST")));
        event.put("body", java.util.Base64.getEncoder().encodeToString(PROJECT_JSON.getBytes()));
        event.put("isBase64Encoded", true);
        assertEquals(201, handler.handleRequest(event, null).get("statusCode"));
    }

    @Test
    void databaseFailuresBecomeServerErrors() {
        handler = handler(new InMemoryProjectStore() {
            @Override
            public java.util.List<com.acme.models.Project> findAll(boolean archived) {
                throw new StorageException("connection refused", null);
            }
        }, new InMemoryAuditStore());

        Map<String, Object> response = send("GET", "/projects", null);
        assertEquals(500, response.get("statusCode"));
        assertEquals("Database error", error(response));
    }
}
