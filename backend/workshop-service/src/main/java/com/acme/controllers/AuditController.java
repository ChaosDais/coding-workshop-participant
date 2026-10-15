package com.acme.controllers;

import com.acme.http.Access;
import com.acme.http.ApiException;
import com.acme.http.ApiRequest;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.AuditQuery;
import com.acme.models.Role;
import com.acme.services.AuditService;


import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
/**
 * GET /audit: audit entries, newest first; admins only. Optional filters: projectId, milestoneId, action (one or
 * more, comma-separated), from and to (YYYY-MM-DD, inclusive), q (text in the action, actor or details) and limit
 * (default 100, at most 500).
 * GET /audit/page: the same, from {@code offset} (default 0), as {entries, total, offset, limit}, for paging.
 */
public class AuditController implements Controller {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void register(Router router) {
        router.get("/audit", Access.roles(Role.ADMIN), request -> ApiResponse.ok(auditService.find(query(request))))
                .get("/audit/page", Access.roles(Role.ADMIN), request -> ApiResponse.ok(
                        auditService.page(query(request))));
    }

    private static AuditQuery query(ApiRequest request) {
        String actions = request.query("action");
        return new AuditQuery(request.query("projectId"), request.query("milestoneId"),
                actions == null ? List.of() : Arrays.asList(actions.split(",")), date(request, "from"),
                date(request, "to"), request.query("q"), number(request, "limit", AuditQuery.DEFAULT_LIMIT),
                number(request, "offset", 0));
    }

    private static LocalDate date(ApiRequest request, String name) {
        String value = request.query(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(400, name + " must use the format YYYY-MM-DD");
        }
    }

    private static int number(ApiRequest request, String name, int fallback) {
        String value = request.query(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be a whole number");
        }
    }
}
