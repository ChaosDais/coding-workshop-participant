package com.acme.users;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Role;
import com.acme.models.User;

/**
 * Account management under /users; DELETE archives a user, and ?archived=true lists archived ones.
 * Admins do everything; managers can only list and look up their own team (checked in {@link UserService}).
 */
public class UserController implements Controller {
    private static final Access ADMIN_ONLY = Access.roles(Role.ADMIN);
    private static final Access ADMINS_AND_MANAGERS = Access.roles(Role.ADMIN, Role.MANAGER);

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.get("/users", ADMINS_AND_MANAGERS, request -> ApiResponse.ok(
                        service.findAll(request.booleanQuery("archived"), request.user())))
                .post("/users", ADMIN_ONLY, request -> ApiResponse.created(service.create(
                        request.body(User.class), request.field("password"), request.user())))
                .get("/users/{id}", ADMINS_AND_MANAGERS, request -> ApiResponse.ok(
                        service.require(request.param("id"), request.user())))
                .put("/users/{id}", ADMIN_ONLY, request -> ApiResponse.ok(service.update(
                        request.param("id"), request.body(User.class), request.user())))
                .delete("/users/{id}", ADMIN_ONLY, request -> {
                    service.archive(request.param("id"), request.user());
                    return ApiResponse.noContent();
                })
                .post("/users/{id}/restore", ADMIN_ONLY, request -> ApiResponse.ok(
                        service.restore(request.param("id"), request.user())))
                .put("/users/{id}/manager", ADMIN_ONLY, request -> ApiResponse.ok(service.moveEmployee(
                        request.param("id"), request.field("managerId"), request.user())))
                .put("/users/{id}/password", ADMIN_ONLY, request -> {
                    service.resetPassword(request.param("id"), request.field("password"), request.user());
                    return ApiResponse.noContent();
                });
    }
}
