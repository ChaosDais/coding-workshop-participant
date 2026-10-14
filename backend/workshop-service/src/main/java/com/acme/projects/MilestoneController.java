package com.acme.projects;

import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Milestone;

/** Milestones of one project, under /projects/{projectId}/milestones; DELETE archives a milestone. */
public class MilestoneController implements Controller {
    private final ProjectService service;

    public MilestoneController(ProjectService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.post("/projects/{projectId}/milestones", request -> ApiResponse.created(
                        service.addMilestone(request.param("projectId"), request.body(Milestone.class))))
                .put("/projects/{projectId}/milestones/{milestoneId}", request -> ApiResponse.ok(
                        service.updateMilestone(request.param("projectId"), request.param("milestoneId"),
                                request.body(Milestone.class))))
                .delete("/projects/{projectId}/milestones/{milestoneId}", request -> {
                    service.archiveMilestone(request.param("projectId"), request.param("milestoneId"));
                    return ApiResponse.noContent();
                });
    }
}
