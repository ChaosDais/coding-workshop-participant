package com.acme;

import com.acme.models.CompletionStatus;
import com.acme.models.Milestone;
import com.acme.models.Project;
import com.acme.repos.AuditStore;
import com.acme.repos.PostgresAuditStore;
import com.acme.repos.PostgresDatabase;
import com.acme.repos.PostgresProjectStore;
import com.acme.repos.ProjectStore;
import com.acme.repos.StorageException;
import com.acme.services.AuditService;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes Lambda function URL requests:
 * <pre>
 * GET    /                                        service message
 * GET    /audit                                   recent audit entries, newest first
 * GET    /projects                                all projects
 * POST   /projects                                create a project
 * GET    /projects/{id}                           one project
 * PUT    /projects/{id}                           update a project (milestones are kept unless given)
 * DELETE /projects/{id}                           delete a project and its milestones
 * POST   /projects/{id}/milestones                add a milestone
 * PUT    /projects/{id}/milestones/{milestoneId}  update a milestone
 * DELETE /projects/{id}/milestones/{milestoneId}  delete a milestone
 * </pre>
 * Every change is recorded as an audit entry.
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class, new LocalDateAdapter().nullSafe())
            .registerTypeAdapter(CompletionStatus.class, (JsonDeserializer<CompletionStatus>) (json, type, context) ->
                    parseStatus(json.getAsString()))
            .create();
    private static final String ACTOR = "system";

    private final AuditService auditService;
    private final ProjectStore projects;
    private final MessageService messageService;

    /** Used by the Lambda runtime; the Spring context and its database connection are shared across invocations. */
    public Handler() {
        this(SpringContextHolder.CONTEXT.getBean(AuditService.class),
                SpringContextHolder.CONTEXT.getBean(ProjectStore.class),
                SpringContextHolder.CONTEXT.getBean(MessageService.class));
    }

    Handler(AuditService auditService, ProjectStore projects, MessageService messageService) {
        this.auditService = auditService;
        this.projects = projects;
        this.messageService = messageService;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> request = event == null ? Map.of() : event;
        try {
            return route(httpMethod(request), pathSegments(request), request);
        } catch (ApiException e) {
            return errorResponse(e.statusCode, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown by the model constructors when a field is invalid.
            return errorResponse(400, e.getMessage());
        } catch (StorageException e) {
            e.printStackTrace();
            return errorResponse(500, "Database error");
        }
    }

    private Map<String, Object> route(String method, List<String> path, Map<String, Object> request) {
        if (path.isEmpty() && method.equals("GET")) {
            return jsonResponse(200, Map.of("message", messageService.getMessage()));
        }
        if (path.equals(List.of("audit")) && method.equals("GET")) {
            return jsonResponse(200, auditService.getAuditEntriesAsMaps());
        }
        if (!path.isEmpty() && path.get(0).equals("projects")) {
            boolean milestonePath = path.size() >= 3 && path.get(2).equals("milestones");
            switch (path.size() + " " + method) {
                case "1 GET":
                    return jsonResponse(200, projects.findAll());
                case "1 POST":
                    return createProject(request);
                case "2 GET":
                    return jsonResponse(200, requireProject(path.get(1)));
                case "2 PUT":
                    return updateProject(path.get(1), request);
                case "2 DELETE":
                    return deleteProject(path.get(1));
                case "3 POST":
                    if (milestonePath) {
                        return addMilestone(path.get(1), request);
                    }
                    break;
                case "4 PUT":
                    if (milestonePath) {
                        return updateMilestone(path.get(1), path.get(3), request);
                    }
                    break;
                case "4 DELETE":
                    if (milestonePath) {
                        return deleteMilestone(path.get(1), path.get(3));
                    }
                    break;
                default:
                    break;
            }
        }
        throw new ApiException(404, "No route for " + method + " /" + String.join("/", path));
    }

    private Map<String, Object> createProject(Map<String, Object> request) {
        Project project = convert(parseBody(request), Project.class);
        if (projects.findById(project.id()).isPresent()) {
            throw new ApiException(409, "A project with id " + project.id() + " already exists");
        }
        projects.insert(project);
        audit("project_created", project, null);
        return jsonResponse(201, project);
    }

    private Map<String, Object> updateProject(String id, Map<String, Object> request) {
        Project existing = requireProject(id);
        JsonObject json = parseBody(request);
        Project body = convert(json, Project.class);
        // A body without a milestones field leaves the project's milestones as they are.
        Project updated = new Project(id, body.name(), body.startDate(), body.endDate(), body.progressTime(),
                body.manager(), body.budget(), body.status(),
                json.has("milestones") ? body.milestones() : existing.milestones());
        save(updated);
        audit("project_updated", updated, null);
        return jsonResponse(200, updated);
    }

    private Map<String, Object> deleteProject(String id) {
        Project project = requireProject(id);
        projects.deleteById(id);
        audit("project_deleted", project, null);
        return emptyResponse();
    }

    private Map<String, Object> addMilestone(String projectId, Map<String, Object> request) {
        Project project = requireProject(projectId);
        Milestone milestone = convert(parseBody(request), Milestone.class);
        if (project.milestones().stream().anyMatch(existing -> existing.id().equals(milestone.id()))) {
            throw new ApiException(409, "A milestone with id " + milestone.id() + " already exists");
        }
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.add(milestone);
        save(withMilestones(project, milestones));
        audit("milestone_added", project, milestone.id());
        return jsonResponse(201, milestone);
    }

    private Map<String, Object> updateMilestone(String projectId, String milestoneId, Map<String, Object> request) {
        Project project = requireProject(projectId);
        int index = requireMilestoneIndex(project, milestoneId);
        Milestone body = convert(parseBody(request), Milestone.class);
        Milestone updated = new Milestone(milestoneId, body.name(), body.startDate(), body.endDate(),
                body.progressTime(), body.assignedEmployees(), body.status());
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.set(index, updated);
        save(withMilestones(project, milestones));
        audit("milestone_updated", project, milestoneId);
        return jsonResponse(200, updated);
    }

    private Map<String, Object> deleteMilestone(String projectId, String milestoneId) {
        Project project = requireProject(projectId);
        List<Milestone> milestones = new ArrayList<>(project.milestones());
        milestones.remove(requireMilestoneIndex(project, milestoneId));
        save(withMilestones(project, milestones));
        audit("milestone_deleted", project, milestoneId);
        return emptyResponse();
    }

    private Project requireProject(String id) {
        return projects.findById(id).orElseThrow(() -> new ApiException(404, "Project " + id + " not found"));
    }

    private static int requireMilestoneIndex(Project project, String milestoneId) {
        for (int index = 0; index < project.milestones().size(); index++) {
            if (project.milestones().get(index).id().equals(milestoneId)) {
                return index;
            }
        }
        throw new ApiException(404, "Milestone " + milestoneId + " not found");
    }

    private static Project withMilestones(Project project, List<Milestone> milestones) {
        return new Project(project.id(), project.name(), project.startDate(), project.endDate(),
                project.progressTime(), project.manager(), project.budget(), project.status(), milestones);
    }

    private void save(Project project) {
        if (!projects.replace(project)) {
            // Deleted by another request between the lookup and the write.
            throw new ApiException(404, "Project " + project.id() + " not found");
        }
    }

    private void audit(String action, Project project, String milestoneId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectId", project.id());
        details.put("projectName", project.name());
        if (milestoneId != null) {
            details.put("milestoneId", milestoneId);
        }
        auditService.recordAction(action, ACTOR, details);
    }

    private static JsonObject parseBody(Map<String, Object> request) {
        JsonElement json;
        try {
            json = GSON.fromJson(requestBody(request), JsonElement.class);
        } catch (JsonParseException e) {
            throw new ApiException(400, "Request body is not valid JSON");
        }
        if (json == null || !json.isJsonObject()) {
            throw new ApiException(400, "Request body must be a JSON object");
        }
        return json.getAsJsonObject();
    }

    private static <T> T convert(JsonObject json, Class<T> type) {
        try {
            return GSON.fromJson(json, type);
        } catch (RuntimeException e) {
            // Gson wraps exceptions thrown by the record constructors, so the validation message is in the cause.
            if (e.getCause() instanceof IllegalArgumentException cause) {
                throw new ApiException(400, cause.getMessage());
            }
            if (e instanceof IllegalArgumentException) {
                throw new ApiException(400, e.getMessage());
            }
            if (e instanceof JsonParseException || e instanceof IllegalStateException) {
                throw new ApiException(400, "Request body has a field of the wrong type");
            }
            throw e;
        }
    }

    private static Map<String, Object> jsonResponse(int statusCode, Object body) {
        Map<String, Object> response = emptyResponse();
        response.put("statusCode", statusCode);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        response.put("headers", headers);

        response.put("body", GSON.toJson(body));
        return response;
    }

    private static Map<String, Object> emptyResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 204);
        return response;
    }

    private static Map<String, Object> errorResponse(int statusCode, String message) {
        return jsonResponse(statusCode, Map.of("error", String.valueOf(message)));
    }

    /** Function URL events carry the method under requestContext.http; a direct invocation counts as GET. */
    private static String httpMethod(Map<String, Object> request) {
        if (request.get("requestContext") instanceof Map<?, ?> requestContext
                && requestContext.get("http") instanceof Map<?, ?> http
                && http.get("method") != null) {
            return http.get("method").toString().toUpperCase();
        }
        return "GET";
    }

    /** Path split on slashes, without the /api/{service} prefix that requests through CloudFront carry. */
    private static List<String> pathSegments(Map<String, Object> request) {
        List<String> segments = Arrays.stream(readString(request, "rawPath", "").split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList();
        return segments.size() >= 2 && segments.get(0).equals("api") ? segments.subList(2, segments.size()) : segments;
    }

    private static String requestBody(Map<String, Object> request) {
        String body = readString(request, "body", "");
        if (Boolean.TRUE.equals(request.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return body;
    }

    private static String readString(Map<String, Object> event, String key, String defaultValue) {
        Object value = event.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private static CompletionStatus parseStatus(String value) {
        try {
            return CompletionStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be one of TODO, IN_PROGRESS, COMPLETE");
        }
    }

    /** Ends the request with the given HTTP status and error message. */
    private static class ApiException extends RuntimeException {
        private final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private static class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            String value = in.nextString();
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Dates must use the format YYYY-MM-DD: " + value);
            }
        }
    }

    /** Defers building the Spring context until the Lambda runtime creates the handler. */
    private static class SpringContextHolder {
        private static final AnnotationConfigApplicationContext CONTEXT =
                new AnnotationConfigApplicationContext(SpringConfig.class);
    }

    @Configuration
    static class SpringConfig {
        @Bean
        public MessageService messageService() {
            return new MessageService();
        }

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
    }

    public static class MessageService {
        public String getMessage() {
            return "Hello from Spring Boot Lambda";
        }
    }
}
