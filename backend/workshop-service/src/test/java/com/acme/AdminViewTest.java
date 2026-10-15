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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the admin screens need: filtered audit history and archived milestones. */
class AdminViewTest {
    private final TestApp app = new TestApp();
    private String token;
    private String projectId;
    private String designId;
    private String buildId;

    @BeforeEach
    void setUp() {
        token = app.setUpAdmin();
        String managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        projectId = body(send("POST", "/projects", """
                {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "manager": "%s",
                 "budget": 1000, "status": "IN_PROGRESS"}""".formatted(managerId))).getAsJsonObject()
                .get("id").getAsString();
        designId = addMilestone("Design");
        buildId = addMilestone("Build");
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    private String addMilestone(String name) {
        return body(send("POST", "/projects/" + projectId + "/milestones", """
                {"name": "%s", "startDate": "2026-02-01", "endDate": "2026-03-01", "status": "TODO"}"""
                .formatted(name))).getAsJsonObject().get("id").getAsString();
    }

    private List<String> actions(String query) {
        Map<String, Object> response = send("GET", "/audit" + query, null);
        assertEquals(200, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonArray().asList().stream()
                .map(entry -> entry.getAsJsonObject().get("action").getAsString()).toList();
    }

    @Test
    void theAuditLogFiltersByProjectMilestoneActionTextAndDate() {
        assertEquals(List.of("milestone_added", "milestone_added", "project_created"),
                actions("?projectId=" + projectId));
        assertEquals(List.of("milestone_added"), actions("?milestoneId=" + buildId));
        assertEquals(List.of("project_created", "user_created"),
                actions("?action=project_created,user_created"));
        // Text matches the action, actor or details, ignoring case.
        assertEquals(List.of("user_created"), actions("?q=MANAGER@example"));
        assertEquals(2, actions("?limit=2").size());

        assertEquals(6, actions("?from=2026-10-14&to=2026-10-14").size());
        assertTrue(actions("?from=2026-10-15").isEmpty());
        app.clock.advance(Duration.ofDays(2));
        token = app.signIn(TestApp.ADMIN_EMAIL, PASSWORD).get("accessToken").getAsString();
        send("DELETE", "/projects/" + projectId + "/milestones/" + designId, null);
        assertEquals(List.of("milestone_archived"), actions("?from=2026-10-16&projectId=" + projectId));
        assertEquals(List.of("milestone_added", "milestone_added", "project_created"),
                actions("?to=2026-10-15&projectId=" + projectId));

        assertEquals("from must use the format YYYY-MM-DD", error(send("GET", "/audit?from=14/10/2026", null)));
        assertEquals("to must not be before from", error(send("GET", "/audit?from=2026-10-15&to=2026-10-14", null)));
        assertEquals("limit must be between 1 and 500", error(send("GET", "/audit?limit=0", null)));
        assertEquals("limit must be a whole number", error(send("GET", "/audit?limit=lots", null)));
    }

    @Test
    void archivedMilestonesCanBeListed() {
        assertEquals(204, status(send("DELETE", "/projects/" + projectId + "/milestones/" + designId, null)));

        assertEquals(List.of(buildId), ids(send("GET", "/projects/" + projectId + "/milestones", null)));
        assertEquals(List.of(designId), ids(send("GET", "/projects/" + projectId + "/milestones?archived=true",
                null)));

        // Admins can also look inside an archived project.
        assertEquals(204, status(send("DELETE", "/projects/" + projectId, null)));
        assertEquals(List.of(buildId), ids(send("GET", "/projects/" + projectId + "/milestones", null)));
        assertEquals(List.of(designId), ids(send("GET", "/projects/" + projectId + "/milestones?archived=true",
                null)));
        assertEquals(404, status(send("GET", "/projects/missing/milestones", null)));

        String managerToken = app.signIn("manager@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(404, status(app.send("GET", "/projects/" + projectId + "/milestones?archived=true", null,
                managerToken)));
    }

    @Test
    void auditEntriesNameTheMilestonesAndPeopleTheyAreAbout() {
        assertEquals(204, status(send("DELETE", "/projects/" + projectId + "/milestones/" + designId, null)));
        assertEquals(Map.of("projectId", projectId, "projectName", "Apollo", "milestoneId", designId,
                "milestoneName", "Design", "actorName", "Administrator"), details("?action=milestone_archived"));
        assertEquals("Build", details("?action=milestone_added&milestoneId=" + buildId).get("milestoneName"));

        String employeeId = app.createUser(token, "eli@example.com", "EMPLOYEE", null);
        assertEquals(204, status(send("DELETE", "/users/" + employeeId, null)));
        Map<String, Object> created = details("?action=user_created&q=eli@example.com");
        assertEquals("eli@example.com", created.get("name"));
        assertEquals("eli@example.com", created.get("email"));
        assertEquals("eli@example.com", details("?action=user_archived").get("name"));
    }

    @Test
    void adminsAdjustTheBottleneckThresholdsEveryoneReads() {
        JsonObject defaults = body(send("GET", "/settings/bottlenecks", null)).getAsJsonObject();
        assertEquals(3, defaults.get("awaitingReviewDays").getAsInt());
        assertEquals(30, defaults.get("anyStatusDays").getAsInt());

        assertEquals(200, status(send("PUT", "/settings/bottlenecks",
                "{\"awaitingReviewDays\": 5, \"anyStatusDays\": 21}")));
        String managerToken = app.signIn("manager@example.com", PASSWORD).get("accessToken").getAsString();
        JsonObject read = body(app.send("GET", "/settings/bottlenecks", null, managerToken)).getAsJsonObject();
        assertEquals(5, read.get("awaitingReviewDays").getAsInt());
        assertEquals(21, read.get("anyStatusDays").getAsInt());

        assertEquals(403, status(app.send("PUT", "/settings/bottlenecks",
                "{\"awaitingReviewDays\": 1, \"anyStatusDays\": 1}", managerToken)));
        assertEquals("anyStatusDays must be a whole number of days from 1 to 365", error(send("PUT",
                "/settings/bottlenecks", "{\"awaitingReviewDays\": 5, \"anyStatusDays\": 0}")));
        assertEquals(400, status(send("PUT", "/settings/bottlenecks", "{\"awaitingReviewDays\": 5}")));
        assertEquals("{\"awaitingReviewDays\":3,\"anyStatusDays\":30}",
                details("?action=settings_updated").get("previous"));
    }

    /** The details of the newest audit entry matching the query. */
    @Test
    void theAuditLogComesInPagesWithTheTotal() {
        addMilestone("Test");
        addMilestone("Launch");
        addMilestone("Review");
        List<JsonElement> all = body(send("GET", "/audit?limit=500", null)).getAsJsonArray().asList();
        JsonObject first = body(send("GET", "/audit/page?limit=3", null)).getAsJsonObject();
        assertEquals(all.size(), first.get("total").getAsInt());
        assertEquals(all.subList(0, 3), first.getAsJsonArray("entries").asList());
        // The last page holds what is left: here, the oldest entry alone.
        int lastOffset = all.size() - 1;
        JsonObject last = body(send("GET", "/audit/page?limit=3&offset=" + lastOffset, null)).getAsJsonObject();
        assertEquals(List.of(all.get(lastOffset)), last.getAsJsonArray("entries").asList());
        assertEquals(lastOffset, last.get("offset").getAsInt());
        // Filters apply to the total too.
        assertEquals(5, body(send("GET", "/audit/page?limit=10&action=milestone_added", null)).getAsJsonObject()
                .get("total").getAsInt());
        assertEquals("offset must not be negative", error(send("GET", "/audit/page?offset=-1", null)));
        assertEquals("offset must be a whole number", error(send("GET", "/audit/page?offset=two", null)));
    }

    @Test
    void timeEntriesAndTeamMovesAreLoggedByName() {
        assertEquals(201, status(send("POST", "/projects/" + projectId + "/milestones/" + designId + "/time",
                "{\"hours\": 1}")));
        Map<String, Object> logged = details("?action=time_logged");
        assertEquals("Apollo", logged.get("projectName"));
        assertEquals("Design", logged.get("milestoneName"));
        assertEquals("Administrator", logged.get("employeeName"));
        assertEquals("Administrator", logged.get("actorName"));

        String fromId = app.createUser(token, "from@example.com", "MANAGER", null);
        String toId = app.createUser(token, "to@example.com", "MANAGER", null);
        String employeeId = app.createUser(token, "moved@example.com", "EMPLOYEE", fromId);
        assertEquals(200, status(send("PUT", "/users/" + employeeId + "/manager",
                "{\"managerId\": \"%s\"}".formatted(toId))));
        Map<String, Object> moved = details("?action=employee_moved");
        assertEquals("from@example.com", moved.get("fromManagerName"));
        assertEquals("to@example.com", moved.get("toManagerName"));
    }

    private Map<String, Object> details(String query) {
        JsonElement entry = body(send("GET", "/audit" + query, null)).getAsJsonArray().get(0);
        Map<String, Object> details = new java.util.HashMap<>();
        entry.getAsJsonObject().getAsJsonObject("details").entrySet()
                .forEach(field -> details.put(field.getKey(), field.getValue().getAsString()));
        return details;
    }

    private static List<String> ids(Map<String, Object> response) {
        assertEquals(200, status(response), () -> String.valueOf(response.get("body")));
        return body(response).getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject)
                .map(milestone -> milestone.get("id").getAsString()).toList();
    }
}
