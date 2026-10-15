package com.acme;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.acme.TestApp.ADMIN_EMAIL;
import static com.acme.TestApp.PASSWORD;
import static com.acme.TestApp.body;
import static com.acme.TestApp.error;
import static com.acme.TestApp.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What admins can do with accounts. */
class UsersTest {
    private final TestApp app = new TestApp();
    private String token;
    private String adminId;

    @BeforeEach
    void signInAsAdmin() {
        token = app.setUpAdmin();
        adminId = body(app.send("GET", "/auth/me", null, token)).getAsJsonObject().get("id").getAsString();
    }

    private Map<String, Object> send(String method, String path, String body) {
        return app.send(method, path, body, token);
    }

    @Test
    void adminsCreateUsersOfEveryRoleIncludingOtherAdmins() {
        String secondAdmin = app.createUser(token, "second-admin@example.com", "ADMIN", null);
        String managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        String employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);

        String secondAdminToken = app.signIn("second-admin@example.com", PASSWORD).get("accessToken").getAsString();
        assertEquals(200, status(app.send("GET", "/users", null, secondAdminToken)));

        JsonObject employee = body(send("GET", "/users/" + employeeId, null)).getAsJsonObject();
        assertEquals(managerId, employee.get("managerId").getAsString());
        assertFalse(employee.has("password") || employee.has("passwordHash"));
        assertEquals(4, body(send("GET", "/users", null)).getAsJsonArray().size());
        assertTrue(body(send("GET", "/users", null)).toString().contains(secondAdmin));
    }

    @Test
    void usersHaveAnOptionalJobTypeAndSalary() {
        Map<String, Object> created = send("POST", "/users", """
                {"email": "dev@example.com", "name": "Dev", "role": "EMPLOYEE", "password": "%s",
                 "jobType": "  Software Engineer ", "salary": 72000.50}""".formatted(PASSWORD));
        assertEquals(201, status(created));
        String id = body(created).getAsJsonObject().get("id").getAsString();
        JsonObject stored = body(send("GET", "/users/" + id, null)).getAsJsonObject();
        assertEquals("Software Engineer", stored.get("jobType").getAsString());
        assertEquals(new java.math.BigDecimal("72000.50"), stored.get("salary").getAsBigDecimal());

        String base = "{\"email\": \"x@example.com\", \"name\": \"X\", \"role\": \"EMPLOYEE\", \"password\": \""
                + PASSWORD + "\"";
        assertEquals("salary must be a non-negative number", error(send("POST", "/users", base + ", \"salary\": -1}")));
        assertEquals("jobType is required", error(send("POST", "/users", base + ", \"jobType\": \" \"}")));
        assertEquals("Request body has a field of the wrong type",
                error(send("POST", "/users", base + ", \"salary\": \"lots\"}")));
        // Both are optional: accounts without them are valid and simply omit the fields.
        String plainId = body(send("POST", "/users", base + "}")).getAsJsonObject().get("id").getAsString();
        assertFalse(body(send("GET", "/users/" + plainId, null)).getAsJsonObject().has("salary"));

        // PUT replaces the details, so the new values win and an omitted field is cleared.
        JsonObject raised = body(send("PUT", "/users/" + id, """
                {"email": "dev@example.com", "name": "Dev", "role": "EMPLOYEE", "jobType": "Senior Engineer",
                 "salary": 85000}""")).getAsJsonObject();
        assertEquals("Senior Engineer", raised.get("jobType").getAsString());
        assertEquals(85000, raised.get("salary").getAsInt());
        assertFalse(body(send("PUT", "/users/" + id, "{\"email\": \"dev@example.com\", \"name\": \"Dev\","
                + " \"role\": \"EMPLOYEE\"}")).getAsJsonObject().has("salary"));
    }

    @Test
    void newUsersAreValidated() {
        String managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        String body = "{\"email\": \"%s\", \"name\": \"N\", \"role\": \"%s\", \"password\": \"%s\"%s}";

        assertEquals(409, status(send("POST", "/users",
                body.formatted("MANAGER@example.com", "EMPLOYEE", PASSWORD, ""))));
        assertEquals("role must be one of ADMIN, MANAGER, EMPLOYEE", error(send("POST", "/users",
                body.formatted("x@example.com", "OWNER", PASSWORD, ""))));
        assertEquals("password must be at least 8 characters", error(send("POST", "/users",
                body.formatted("x@example.com", "EMPLOYEE", "short", ""))));
        assertEquals("only employees can have a manager", error(send("POST", "/users",
                body.formatted("x@example.com", "MANAGER", PASSWORD, ", \"managerId\": \"" + managerId + "\""))));
        assertTrue(error(send("POST", "/users", body.formatted("x@example.com", "EMPLOYEE", PASSWORD,
                ", \"managerId\": \"" + adminId + "\""))).equals("The chosen manager is not an active manager"));
    }

    @Test
    void employeesMoveBetweenManagers() {
        String first = app.createUser(token, "first@example.com", "MANAGER", null);
        String second = app.createUser(token, "second@example.com", "MANAGER", null);
        String employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", first);

        Map<String, Object> moved = send("PUT", "/users/" + employeeId + "/manager",
                "{\"managerId\": \"" + second + "\"}");
        assertEquals(200, status(moved));
        assertEquals(second, body(moved).getAsJsonObject().get("managerId").getAsString());
        JsonObject audit = body(send("GET", "/audit", null)).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals("employee_moved", audit.get("action").getAsString());
        assertEquals(first, audit.getAsJsonObject("details").get("fromManagerId").getAsString());
        assertEquals(ADMIN_EMAIL, audit.get("actor").getAsString());

        assertEquals(400, status(send("PUT", "/users/" + second + "/manager", "{\"managerId\": \"" + first + "\"}")));
        assertEquals(400, status(send("PUT", "/users/" + employeeId + "/manager",
                "{\"managerId\": \"" + employeeId + "\"}")));
        assertEquals(200, status(send("PUT", "/users/" + employeeId + "/manager", "{}")));
    }

    @Test
    void archivedUsersAreLockedOutImmediatelyAndCanBeRestored() {
        String managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        JsonObject session = app.signIn("manager@example.com", PASSWORD);
        String managerToken = session.get("accessToken").getAsString();

        assertEquals(204, status(send("DELETE", "/users/" + managerId, null)));
        assertEquals("Account is not active", error(app.send("GET", "/auth/me", null, managerToken)));
        assertEquals(401, status(app.send("POST", "/auth/refresh",
                "{\"refreshToken\": \"" + session.get("refreshToken").getAsString() + "\"}", null)));
        assertEquals("Invalid email or password", error(app.send("POST", "/auth/login",
                "{\"email\": \"manager@example.com\", \"password\": \"" + PASSWORD + "\"}", null)));

        assertEquals(List.of(managerId), body(send("GET", "/users?archived=true", null)).getAsJsonArray().asList()
                .stream().map(user -> user.getAsJsonObject().get("id").getAsString()).toList());
        assertTrue(body(send("GET", "/users/" + managerId, null)).getAsJsonObject().get("archived").getAsBoolean());
        assertEquals(404, status(send("PUT", "/users/" + managerId,
                "{\"email\": \"manager@example.com\", \"name\": \"M\", \"role\": \"MANAGER\"}")));

        assertEquals(200, status(send("POST", "/users/" + managerId + "/restore", null)));
        app.signIn("manager@example.com", PASSWORD);
        assertEquals(404, status(send("POST", "/users/" + managerId + "/restore", null)));
    }

    @Test
    void rulesKeepTheTeamStructureConsistent() {
        assertEquals("You cannot archive your own account", error(send("DELETE", "/users/" + adminId, null)));
        assertEquals("At least one active admin must remain", error(send("PUT", "/users/" + adminId,
                "{\"email\": \"" + ADMIN_EMAIL + "\", \"name\": \"A\", \"role\": \"MANAGER\"}")));

        String managerId = app.createUser(token, "manager@example.com", "MANAGER", null);
        String employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", managerId);
        assertEquals("Move this manager's employees to another manager first",
                error(send("DELETE", "/users/" + managerId, null)));
        assertEquals(200, status(send("PUT", "/users/" + employeeId + "/manager", "{}")));

        assertEquals(201, status(send("POST", "/projects", """
                {"name": "Apollo", "startDate": "2026-01-01", "endDate": "2026-12-31", "progressTime": 0,
                 "manager": "%s", "budget": 1, "status": "TODO"}""".formatted(managerId))));
        assertEquals("Give this manager's projects to another manager first", error(send("PUT", "/users/" + managerId,
                "{\"email\": \"manager@example.com\", \"name\": \"M\", \"role\": \"EMPLOYEE\"}")));
    }

    @Test
    void adminsResetPasswordsAndEditAccounts() {
        String employeeId = app.createUser(token, "employee@example.com", "EMPLOYEE", null);
        String refreshToken = app.signIn("employee@example.com", PASSWORD).get("refreshToken").getAsString();

        assertEquals(204, status(send("PUT", "/users/" + employeeId + "/password",
                "{\"password\": \"reset-pass-1\"}")));
        assertEquals(401, status(app.send("POST", "/auth/refresh", "{\"refreshToken\": \"" + refreshToken + "\"}",
                null)));
        app.signIn("employee@example.com", "reset-pass-1");

        Map<String, Object> updated = send("PUT", "/users/" + employeeId,
                "{\"email\": \"Renamed@Example.com\", \"name\": \"Renamed\", \"role\": \"MANAGER\"}");
        assertEquals(200, status(updated));
        assertEquals("renamed@example.com", body(updated).getAsJsonObject().get("email").getAsString());
        assertEquals("MANAGER", body(updated).getAsJsonObject().get("role").getAsString());
        assertEquals(409, status(send("PUT", "/users/" + employeeId,
                "{\"email\": \"" + ADMIN_EMAIL + "\", \"name\": \"R\", \"role\": \"MANAGER\"}")));
    }
}
