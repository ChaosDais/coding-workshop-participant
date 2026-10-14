package com.acme.timelog;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Role;

import java.math.BigDecimal;

/**
 * Time logging. Employees log hours or run a timer on their milestones; everyone who can see a milestone can list
 * its logged time (pay figures for admins only, checked in {@link TimeService}); admins correct entries.
 */
public class TimeController implements Controller {
    private static final Access EMPLOYEES = Access.roles(Role.EMPLOYEE);
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
        router.post(MILESTONE + "/time", EMPLOYEES, request -> {
                    HoursEntry entry = request.body(HoursEntry.class);
                    return ApiResponse.created(service.logHours(request.param("projectId"),
                            request.param("milestoneId"), entry.hours(), entry.note(), request.user()));
                })
                .get(MILESTONE + "/time", Access.SIGNED_IN, request -> ApiResponse.ok(service.findForMilestone(
                        request.param("projectId"), request.param("milestoneId"), request.user())))
                .post(MILESTONE + "/timer", EMPLOYEES, request -> ApiResponse.created(service.startTimer(
                        request.param("projectId"), request.param("milestoneId"), request.optionalField("note"),
                        request.user())))
                .get("/timer", EMPLOYEES, request -> ApiResponse.ok(service.runningTimer(request.user())))
                .post("/timer/stop", EMPLOYEES, request -> ApiResponse.ok(service.stopTimer(request.user())))
                .put("/time/{id}", Access.roles(Role.ADMIN), request -> {
                    Correction correction = request.body(Correction.class);
                    return ApiResponse.ok(service.correct(request.param("id"), correction.hours(), correction.note(),
                            correction.reason(), request.user()));
                });
    }
}
