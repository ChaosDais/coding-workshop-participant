package com.acme.projects;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Milestone;
import com.acme.models.Role;

/**
 * Milestones of one project, under /projects/{projectId}/milestones; DELETE archives a milestone.
 * Admins only for now; manager and employee access is still to be defined.
 */
public class MilestoneController implements Controller {
    private static final Access ADMIN_ONLY = Access.roles(Role.ADMIN);

    private final ProjectService service;

    public MilestoneController(ProjectService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.post("/projects/{projectId}/milestones", ADMIN_ONLY, request -> ApiResponse.created(
                        service.addMilestone(request.param("projectId"), request.body(Milestone.class),
                                request.user())))
                .put("/projects/{projectId}/milestones/{milestoneId}", ADMIN_ONLY, request -> ApiResponse.ok(
                        service.updateMilestone(request.param("projectId"), request.param("milestoneId"),
                                request.body(Milestone.class), request.user())))
                .delete("/projects/{projectId}/milestones/{milestoneId}", ADMIN_ONLY, request -> {
                    service.archiveMilestone(request.param("projectId"), request.param("milestoneId"),
                            request.user());
                    return ApiResponse.noContent();
                });
    }
}
