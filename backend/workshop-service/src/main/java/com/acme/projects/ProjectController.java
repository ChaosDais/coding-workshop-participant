package com.acme.projects;

import com.acme.http.ApiException;
import com.acme.http.ApiRequest;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Project;

/** CRUD for /projects; DELETE archives a project, and archived ones are listed with ?archived=true. */
public class ProjectController implements Controller {
    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.get("/projects", request -> ApiResponse.ok(service.findAll(archivedFilter(request))))
                .post("/projects", request -> ApiResponse.created(service.create(request.body(Project.class))))
                .get("/projects/{id}", request -> ApiResponse.ok(service.require(request.param("id"))))
                .put("/projects/{id}", this::update)
                .delete("/projects/{id}", request -> {
                    service.archive(request.param("id"));
                    return ApiResponse.noContent();
                })
                .post("/projects/{id}/restore", request -> ApiResponse.ok(service.restore(request.param("id"))));
    }

    private static boolean archivedFilter(ApiRequest request) {
        String value = request.query("archived");
        if (value == null || value.equals("false")) {
            return false;
        }
        if (value.equals("true")) {
            return true;
        }
        throw new ApiException(400, "archived must be true or false");
    }

    /** A body without a milestones field leaves the project's milestones as they are. */
    private ApiResponse update(ApiRequest request) {
        boolean replaceMilestones = request.json().has("milestones");
        return ApiResponse.ok(service.update(request.param("id"), request.body(Project.class), replaceMilestones));
    }
}
