package com.acme.controllers;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.BottleneckThresholds;
import com.acme.models.Role;
import com.acme.services.SettingsService;

/**
 * GET /settings/bottlenecks: the bottleneck thresholds, for any signed-in user whose screens flag bottlenecks.
 * PUT /settings/bottlenecks: admins change them.
 */
public class SettingsController implements Controller {
    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.get("/settings/bottlenecks", Access.SIGNED_IN, request -> ApiResponse.ok(service.bottleneckThresholds()))
                .put("/settings/bottlenecks", Access.roles(Role.ADMIN), request -> ApiResponse.ok(
                        service.updateBottleneckThresholds(request.body(BottleneckThresholds.class), request.user())));
    }
}
