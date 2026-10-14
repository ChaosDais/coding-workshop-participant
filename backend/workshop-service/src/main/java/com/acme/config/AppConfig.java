package com.acme.config;

import com.acme.controllers.AuditController;
import com.acme.controllers.AuthController;
import com.acme.controllers.MilestoneController;
import com.acme.controllers.ProjectController;
import com.acme.controllers.SettingsController;
import com.acme.controllers.StatusController;
import com.acme.controllers.TimeController;
import com.acme.controllers.UserController;
import com.acme.http.Authenticator;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.repos.AuditRepository;
import com.acme.repos.PostgresAuditRepository;
import com.acme.repos.PostgresDatabase;
import com.acme.repos.PostgresProjectRepository;
import com.acme.repos.PostgresRefreshTokenRepository;
import com.acme.repos.PostgresSettingsRepository;
import com.acme.repos.PostgresTimeEntryRepository;
import com.acme.repos.PostgresUserRepository;
import com.acme.repos.ProjectRepository;
import com.acme.repos.RefreshTokenRepository;
import com.acme.repos.SettingsRepository;
import com.acme.repos.TimeEntryRepository;
import com.acme.repos.UserRepository;
import com.acme.services.AuditService;
import com.acme.services.AuthService;
import com.acme.services.PasswordHasher;
import com.acme.services.ProjectService;
import com.acme.services.SettingsService;
import com.acme.services.TimeService;
import com.acme.services.TokenAuthenticator;
import com.acme.services.TokenService;
import com.acme.services.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/** Wires the repositories, services and controllers; the database settings come from the POSTGRES_* variables. */
@Configuration
public class AppConfig {
    private static final int BCRYPT_STRENGTH = 10;

    @Bean
    public PostgresDatabase postgresDatabase() {
        return PostgresDatabase.fromEnvironment(System.getenv());
    }

    @Bean
    public ProjectRepository projectRepository(PostgresDatabase database) {
        return new PostgresProjectRepository(database);
    }

    @Bean
    public AuditRepository auditRepository(PostgresDatabase database) {
        return new PostgresAuditRepository(database);
    }

    @Bean
    public UserRepository userRepository(PostgresDatabase database) {
        return new PostgresUserRepository(database);
    }

    @Bean
    public RefreshTokenRepository refreshTokenRepository(PostgresDatabase database) {
        return new PostgresRefreshTokenRepository(database);
    }

    @Bean
    public SettingsRepository settingsRepository(PostgresDatabase database) {
        return new PostgresSettingsRepository(database);
    }

    @Bean
    public TimeEntryRepository timeEntryRepository(PostgresDatabase database) {
        return new PostgresTimeEntryRepository(database);
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
    public TokenService tokenService(SettingsRepository settingsRepository,
                                     RefreshTokenRepository refreshTokenRepository, Clock clock) {
        return new TokenService(settingsRepository, refreshTokenRepository, clock);
    }

    @Bean
    public Authenticator authenticator(TokenService tokenService, UserRepository userRepository) {
        return new TokenAuthenticator(tokenService, userRepository);
    }

    @Bean
    public AuditService auditService(AuditRepository auditRepository, Clock clock) {
        return new AuditService(auditRepository, clock);
    }

    @Bean
    public AuthService authService(UserRepository userRepository, PasswordHasher passwordHasher,
                                   TokenService tokenService, AuditService auditService) {
        return new AuthService(userRepository, passwordHasher, tokenService, auditService,
                AuthService.DefaultAdmin.fromEnvironment(System.getenv()));
    }

    @Bean
    public UserService userService(UserRepository userRepository, ProjectRepository projectRepository,
                                   PasswordHasher passwordHasher, TokenService tokenService,
                                   AuditService auditService) {
        return new UserService(userRepository, projectRepository, passwordHasher, tokenService, auditService);
    }

    @Bean
    public ProjectService projectService(ProjectRepository projectRepository, UserRepository userRepository,
                                         TimeEntryRepository timeEntryRepository, AuditService auditService,
                                         Clock clock) {
        return new ProjectService(projectRepository, userRepository, timeEntryRepository, auditService, clock);
    }

    @Bean
    public TimeService timeService(TimeEntryRepository timeEntryRepository, ProjectRepository projectRepository,
                                   UserRepository userRepository, AuditService auditService, Clock clock) {
        return new TimeService(timeEntryRepository, projectRepository, userRepository, auditService, clock);
    }

    @Bean
    public SettingsService settingsService(SettingsRepository settingsRepository, AuditService auditService) {
        return new SettingsService(settingsRepository, auditService);
    }

    /**
     * Every endpoint the service exposes; a new feature adds its controller here. Each route declares its own
     * access rule when it registers, and the router checks it before the route runs.
     */
    @Bean
    public Router router(Authenticator authenticator, AuthService authService, UserService userService,
                         ProjectService projectService, TimeService timeService, AuditService auditService,
                         SettingsService settingsService) {
        Router router = new Router(authenticator);
        List<Controller> controllers = List.of(
                new StatusController(),
                new AuthController(authService),
                new UserController(userService),
                new ProjectController(projectService),
                new MilestoneController(projectService),
                new TimeController(timeService),
                new AuditController(auditService),
                new SettingsController(settingsService));
        controllers.forEach(controller -> controller.register(router));
        return router;
    }
}
