package com.acme.projects;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Role;

/**
 * Milestones of one project, under /projects/{projectId}/milestones; DELETE archives a milestone.
 * Admins and the project's manager; employees can only move their own milestones to AWAITING_REVIEW through
 * PUT .../status (checked in {@link ProjectService}).
 */
public class MilestoneController implements Controller {
    private static final Access ADMINS_AND_MANAGERS = Access.roles(Role.ADMIN, Role.MANAGER);

    private final ProjectService service;

    public MilestoneController(ProjectService service) {
        this.service = service;
    }

    /** Body of PUT .../status: {"status": "AWAITING_REVIEW"}. */
    private record StatusChange(CompletionStatus status) {
    }

    @Override
    public void register(Router router) {
        router.post("/projects/{projectId}/milestones", ADMINS_AND_MANAGERS, request -> ApiResponse.created(
                        service.addMilestone(request.param("projectId"), request.body(Milestone.class),
                                request.user())))
                .put("/projects/{projectId}/milestones/{milestoneId}", ADMINS_AND_MANAGERS, request -> ApiResponse.ok(
                        service.updateMilestone(request.param("projectId"), request.param("milestoneId"),
                                request.body(Milestone.class), request.user())))
                .put("/projects/{projectId}/milestones/{milestoneId}/status", Access.SIGNED_IN, request -> ApiResponse.ok(
                        service.changeMilestoneStatus(request.param("projectId"), request.param("milestoneId"),
                                request.body(StatusChange.class).status(), request.user())))
                .delete("/projects/{projectId}/milestones/{milestoneId}", ADMINS_AND_MANAGERS, request -> {
                    service.archiveMilestone(request.param("projectId"), request.param("milestoneId"),
                            request.user());
                    return ApiResponse.noContent();
                });
    }
}
