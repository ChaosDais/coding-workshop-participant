package com.acme.audit;

import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;

/** GET /audit: recent audit entries, newest first. */
public class AuditController implements Controller {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void register(Router router) {
        router.get("/audit", request -> ApiResponse.ok(auditService.getAuditEntriesAsMaps()));
    }
}
