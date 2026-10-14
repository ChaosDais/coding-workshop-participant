package com.acme.audit;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.models.Role;

/** GET /audit: recent audit entries, newest first; admins only. */
public class AuditController implements Controller {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void register(Router router) {
        router.get("/audit", Access.roles(Role.ADMIN), request -> ApiResponse.ok(auditService.getAuditEntriesAsMaps()));
    }
}
