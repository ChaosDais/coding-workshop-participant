package com.acme;

import com.acme.repos.InMemoryAuditStore;
import com.acme.repos.InMemoryProjectStore;
import com.acme.services.AuditService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerTest {
    private static final String PROJECT_JSON = """
            {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 12.5,
             "manager": "alice", "budget": 50000.75, "status": "IN_PROGRESS"}""";
    private static final String MILESTONE_JSON = """
            {"name": "Design", "startDate": "2026-01-01", "endDate": "2026-02-01", "progressTime": 4,
             "assignedEmployees": ["bob", "carol"], "status": "TODO"}""";

    private final Handler handler = new Handler(new AuditService(new InMemoryAuditStore()),
            new InMemoryProjectStore(), new Handler.MessageService());

    /** Mimics the Lambda function URL event. */
    private Map<String, Object> send(String method, String path, String body) {
        Map<String, Object> event = new HashMap<>();
        event.put("rawPath", path);
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
    void projectAndMilestoneLifecycle() {
        assertEquals(200, send("GET", "/", null).get("statusCode"));
        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());

        Map<String, Object> created = send("POST", "/projects", PROJECT_JSON);
        assertEquals(201, created.get("statusCode"));
        String id = body(created).getAsJsonObject().get("id").getAsString();
        assertEquals(409, send("POST", "/projects",
                PROJECT_JSON.replaceFirst("\\{", "{\"id\": \"" + id + "\", ")).get("statusCode"));

        Map<String, Object> milestone = send("POST", "/projects/" + id + "/milestones", MILESTONE_JSON);
        assertEquals(201, milestone.get("statusCode"));
        String milestoneId = body(milestone).getAsJsonObject().get("id").getAsString();

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

        assertEquals(204, send("DELETE", "/projects/" + id + "/milestones/" + milestoneId, null).get("statusCode"));
        assertEquals(404, send("DELETE", "/projects/" + id + "/milestones/" + milestoneId, null).get("statusCode"));
        assertEquals(204, send("DELETE", "/projects/" + id, null).get("statusCode"));
        assertEquals(404, send("GET", "/projects/" + id, null).get("statusCode"));

        // Every change was audited, newest first; reads and rejected requests were not.
        JsonArray audit = body(send("GET", "/audit", null)).getAsJsonArray();
        List<String> actions = audit.asList().stream()
                .map(entry -> entry.getAsJsonObject().get("action").getAsString()).toList();
        assertEquals(List.of("project_deleted", "milestone_deleted", "milestone_updated", "project_updated",
                "milestone_added", "project_created"), actions);
        assertEquals(id, audit.get(5).getAsJsonObject().getAsJsonObject("details").get("projectId").getAsString());
    }

    @Test
    void requestsThroughCloudFrontKeepTheirApiPrefix() {
        Map<String, Object> created = send("POST", "/api/workshop-service/projects", PROJECT_JSON);
        assertEquals(201, created.get("statusCode"));
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
        assertEquals(400, send("POST", "/projects", "{ \"name\": ").get("statusCode"));
        assertEquals(400, send("POST", "/projects", "").get("statusCode"));
        assertEquals(400, send("POST", "/projects", "[]").get("statusCode"));
        assertEquals(400, send("POST", "/projects", PROJECT_JSON.replace("12.5", "\"many\"")).get("statusCode"));

        assertEquals(404, send("PUT", "/projects/missing", PROJECT_JSON).get("statusCode"));
        assertEquals(404, send("POST", "/projects/missing/milestones", MILESTONE_JSON).get("statusCode"));
        assertEquals(404, send("GET", "/unknown", null).get("statusCode"));
        assertEquals(404, send("PATCH", "/projects", PROJECT_JSON).get("statusCode"));

        assertEquals(0, body(send("GET", "/projects", null)).getAsJsonArray().size());
        assertFalse(body(send("GET", "/audit", null)).getAsJsonArray().iterator().hasNext());
    }
}
