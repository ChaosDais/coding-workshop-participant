package com.acme.controllers;

import com.acme.http.Access;
import com.acme.http.ApiRequest;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Project;
import com.acme.models.Role;
import com.acme.services.ProjectService;

/**
 * CRUD for /projects; DELETE archives a project, and archived ones are listed with ?archived=true.
 * Admins do everything; managers view and edit the projects they manage; employees view the projects they work
 * on (checked in {@link ProjectService}).
 */
public class ProjectController implements Controller {
    private static final Access ADMIN_ONLY = Access.roles(Role.ADMIN);
    private static final Access ADMINS_AND_MANAGERS = Access.roles(Role.ADMIN, Role.MANAGER);
    private static final Access EVERYONE = Access.SIGNED_IN;

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.get("/projects", EVERYONE, request -> ApiResponse.ok(
                        service.findAll(request.booleanQuery("archived"), request.user())))
                .post("/projects", ADMIN_ONLY, request -> ApiResponse.created(
                        service.create(request.body(Project.class), request.user())))
                .get("/projects/{id}", EVERYONE, request -> ApiResponse.ok(
                        service.get(request.param("id"), request.user())))
                .put("/projects/{id}", ADMINS_AND_MANAGERS, this::update)
                .delete("/projects/{id}", ADMIN_ONLY, request -> {
                    service.archive(request.param("id"), request.user());
                    return ApiResponse.noContent();
                })
                .post("/projects/{id}/restore", ADMIN_ONLY, request -> ApiResponse.ok(
                        service.restore(request.param("id"), request.user())));
    }

    /** A body without a milestones or dependsOn field leaves the project's milestones or dependencies as they are. */
    private ApiResponse update(ApiRequest request) {
        boolean replaceMilestones = request.json().has("milestones");
        boolean replaceDependencies = request.json().has("dependsOn");
        return ApiResponse.ok(service.update(request.param("id"), request.body(Project.class), replaceMilestones,
                replaceDependencies, request.user()));
    }
}
