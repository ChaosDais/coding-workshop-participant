package com.acme.config;

import com.acme.StatusController;
import com.acme.audit.AuditController;
import com.acme.audit.AuditService;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.projects.MilestoneController;
import com.acme.projects.ProjectController;
import com.acme.projects.ProjectService;
import com.acme.repos.AuditStore;
import com.acme.repos.PostgresAuditStore;
import com.acme.repos.PostgresDatabase;
import com.acme.repos.PostgresProjectStore;
import com.acme.repos.ProjectStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wires the stores, services and controllers; the database settings come from the POSTGRES_* variables. */
@Configuration
public class AppConfig {
    @Bean
    public PostgresDatabase postgresDatabase() {
        return PostgresDatabase.fromEnvironment(System.getenv());
    }

    @Bean
    public ProjectStore projectStore(PostgresDatabase database) {
        return new PostgresProjectStore(database);
    }

    @Bean
    public AuditStore auditStore(PostgresDatabase database) {
        return new PostgresAuditStore(database);
    }

    @Bean
    public AuditService auditService(AuditStore auditStore) {
        return new AuditService(auditStore);
    }

    @Bean
    public ProjectService projectService(ProjectStore projectStore, AuditService auditService) {
        return new ProjectService(projectStore, auditService);
    }

    /** Every endpoint the service exposes; a new feature adds its controller here. */
    @Bean
    public Router router(ProjectService projectService, AuditService auditService) {
        Router router = new Router();
        List<Controller> controllers = List.of(
                new StatusController(),
                new ProjectController(projectService),
                new MilestoneController(projectService),
                new AuditController(auditService));
        controllers.forEach(controller -> controller.register(router));
        return router;
    }
}
