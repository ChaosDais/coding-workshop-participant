package com.acme.projects;

import com.acme.http.Access;
import com.acme.http.ApiRequest;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Project;
import com.acme.models.Role;

/**
 * CRUD for /projects; DELETE archives a project, and archived ones are listed with ?archived=true.
 * Admins only for now; manager and employee access is still to be defined.
 */
public class ProjectController implements Controller {
    private static final Access ADMIN_ONLY = Access.roles(Role.ADMIN);

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.get("/projects", ADMIN_ONLY, request -> ApiResponse.ok(service.findAll(request.booleanQuery("archived"))))
                .post("/projects", ADMIN_ONLY, request -> ApiResponse.created(
                        service.create(request.body(Project.class), request.user())))
                .get("/projects/{id}", ADMIN_ONLY, request -> ApiResponse.ok(service.require(request.param("id"))))
                .put("/projects/{id}", ADMIN_ONLY, this::update)
                .delete("/projects/{id}", ADMIN_ONLY, request -> {
                    service.archive(request.param("id"), request.user());
                    return ApiResponse.noContent();
                })
                .post("/projects/{id}/restore", ADMIN_ONLY, request -> ApiResponse.ok(
                        service.restore(request.param("id"), request.user())));
    }

    /** A body without a milestones field leaves the project's milestones as they are. */
    private ApiResponse update(ApiRequest request) {
        boolean replaceMilestones = request.json().has("milestones");
        return ApiResponse.ok(service.update(request.param("id"), request.body(Project.class), replaceMilestones,
                request.user()));
    }
}
