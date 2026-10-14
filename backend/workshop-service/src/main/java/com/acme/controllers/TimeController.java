package com.acme.controllers;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Role;
import com.acme.services.TimeService;

import java.math.BigDecimal;

/**
 * Time logging. Everyone logs their own hours or runs a timer (employees on their milestones, managers on their
 * projects, admins anywhere); everyone who can see a milestone can list
 * its logged time (pay figures for admins only, checked in {@link TimeService}); admins correct entries.
 */
public class TimeController implements Controller {
    /** Anyone signed in logs their own time; TimeService decides on which milestones. */
    private static final Access WORKERS = Access.SIGNED_IN;
    private static final String MILESTONE = "/projects/{projectId}/milestones/{milestoneId}";

    /** Body of POST .../time: {"hours": 1.5, "note": "optional"}. */
    private record HoursEntry(BigDecimal hours, String note) {
    }

    /** Body of PUT /time/{id}: {"hours": 1.25, "reason": "required", "note": "optional, kept when absent"}. */
    private record Correction(BigDecimal hours, String note, String reason) {
    }

    private final TimeService service;

    public TimeController(TimeService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.post(MILESTONE + "/time", WORKERS, request -> {
                    HoursEntry entry = request.body(HoursEntry.class);
                    return ApiResponse.created(service.logHours(request.param("projectId"),
                            request.param("milestoneId"), entry.hours(), entry.note(), request.user()));
                })
                .get(MILESTONE + "/time", Access.SIGNED_IN, request -> ApiResponse.ok(service.findForMilestone(
                        request.param("projectId"), request.param("milestoneId"), request.user())))
                .post(MILESTONE + "/timer", WORKERS, request -> ApiResponse.created(service.startTimer(
                        request.param("projectId"), request.param("milestoneId"), request.optionalField("note"),
                        request.user())))
                .get("/timer", WORKERS, request -> ApiResponse.ok(service.runningTimer(request.user())))
                .post("/timer/stop", WORKERS, request -> ApiResponse.ok(service.stopTimer(request.user())))
                .put("/time/{id}", Access.roles(Role.ADMIN), request -> {
                    Correction correction = request.body(Correction.class);
                    return ApiResponse.ok(service.correct(request.param("id"), correction.hours(), correction.note(),
                            correction.reason(), request.user()));
                });
    }
}
