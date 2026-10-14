package com.acme.config;

import com.acme.StatusController;
import com.acme.audit.AuditController;
import com.acme.audit.AuditService;
import com.acme.auth.AuthController;
import com.acme.auth.AuthService;
import com.acme.auth.PasswordHasher;
import com.acme.auth.TokenAuthenticator;
import com.acme.auth.TokenService;
import com.acme.http.Authenticator;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.projects.MilestoneController;
import com.acme.projects.ProjectController;
import com.acme.projects.ProjectService;
import com.acme.repos.AuditStore;
import com.acme.repos.PostgresAuditStore;
import com.acme.repos.PostgresDatabase;
import com.acme.repos.PostgresProjectStore;
import com.acme.repos.PostgresRefreshTokenStore;
import com.acme.repos.PostgresSettingsStore;
import com.acme.repos.PostgresTimeEntryStore;
import com.acme.repos.PostgresUserStore;
import com.acme.repos.ProjectStore;
import com.acme.repos.RefreshTokenStore;
import com.acme.repos.SettingsStore;
import com.acme.repos.TimeEntryStore;
import com.acme.repos.UserStore;
import com.acme.timelog.TimeController;
import com.acme.timelog.TimeService;
import com.acme.users.UserController;
import com.acme.users.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Wires the stores, services and controllers; the database settings come from the POSTGRES_* variables. */
@Configuration
public class AppConfig {
    private static final int BCRYPT_STRENGTH = 10;

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
    public UserStore userStore(PostgresDatabase database) {
        return new PostgresUserStore(database);
    }

    @Bean
    public RefreshTokenStore refreshTokenStore(PostgresDatabase database) {
        return new PostgresRefreshTokenStore(database);
    }

    @Bean
    public SettingsStore settingsStore(PostgresDatabase database) {
        return new PostgresSettingsStore(database);
    }

    @Bean
    public TimeEntryStore timeEntryStore(PostgresDatabase database) {
        return new PostgresTimeEntryStore(database);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher(BCRYPT_STRENGTH);
    }

    @Bean
    public TokenService tokenService(SettingsStore settingsStore, RefreshTokenStore refreshTokenStore, Clock clock) {
        return new TokenService(settingsStore, refreshTokenStore, clock);
    }

    @Bean
    public Authenticator authenticator(TokenService tokenService, UserStore userStore) {
        return new TokenAuthenticator(tokenService, userStore);
    }

    @Bean
    public AuditService auditService(AuditStore auditStore) {
        return new AuditService(auditStore);
    }

    @Bean
    public AuthService authService(UserStore userStore, PasswordHasher passwordHasher, TokenService tokenService,
                                   AuditService auditService) {
        return new AuthService(userStore, passwordHasher, tokenService, auditService);
    }

    @Bean
    public UserService userService(UserStore userStore, ProjectStore projectStore, PasswordHasher passwordHasher,
                                   TokenService tokenService, AuditService auditService) {
        return new UserService(userStore, projectStore, passwordHasher, tokenService, auditService);
    }

    @Bean
    public ProjectService projectService(ProjectStore projectStore, UserStore userStore,
                                         TimeEntryStore timeEntryStore, AuditService auditService, Clock clock) {
        return new ProjectService(projectStore, userStore, timeEntryStore, auditService, clock);
    }

    @Bean
    public TimeService timeService(TimeEntryStore timeEntryStore, ProjectStore projectStore, UserStore userStore,
                                   AuditService auditService, Clock clock) {
        return new TimeService(timeEntryStore, projectStore, userStore, auditService, clock);
    }

    /**
     * Every endpoint the service exposes; a new feature adds its controller here. Each route declares its own
     * access rule when it registers, and the router checks it before the route runs.
     */
    @Bean
    public Router router(Authenticator authenticator, AuthService authService, UserService userService,
                         ProjectService projectService, TimeService timeService, AuditService auditService) {
        Router router = new Router(authenticator);
        List<Controller> controllers = List.of(
                new StatusController(),
                new AuthController(authService),
                new UserController(userService),
                new ProjectController(projectService),
                new MilestoneController(projectService),
                new TimeController(timeService),
                new AuditController(auditService));
        controllers.forEach(controller -> controller.register(router));
        return router;
    }
}
