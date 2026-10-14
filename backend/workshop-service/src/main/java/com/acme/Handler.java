package com.acme;

import com.acme.config.AppConfig;
import com.acme.errors.ConflictException;
import com.acme.errors.ForbiddenException;
import com.acme.errors.NotFoundException;
import com.acme.errors.UnauthorizedException;
import com.acme.http.ApiException;
import com.acme.http.ApiRequest;
import com.acme.http.ApiResponse;
import com.acme.http.Router;
import com.acme.repos.StorageException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

/**
 * Lambda entry point: turns the function URL event into a request, lets the router dispatch it to a controller,
 * and maps errors to HTTP responses. The endpoints themselves are listed in {@link AppConfig#router}.
 * Every request is logged as one line (method, path, status, duration) to standard output, which Lambda sends to
 * CloudWatch; server errors also log their stack trace. Bodies are never logged, so passwords stay out of the logs.
 */
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final Router router;

    /** Used by the Lambda runtime; the Spring context and its database connection are shared across invocations. */
    public Handler() {
        this(SpringContextHolder.CONTEXT.getBean(Router.class));
    }

    Handler(Router router) {
        this.router = router;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        long started = System.nanoTime();
        ApiRequest request;
        try {
            request = ApiRequest.fromEvent(event);
        } catch (IllegalArgumentException e) {
            // The only way parsing fails: a body flagged as base64 that is not.
            ApiResponse response = ApiResponse.error(400, "Request body is not valid base64");
            log("(unreadable request)", response, started);
            return response.toLambdaResponse();
        }
        ApiResponse response = respond(request);
        log(request.method() + " " + request.path(), response, started);
        return response.toLambdaResponse();
    }

    private ApiResponse respond(ApiRequest request) {
        try {
            return router.dispatch(request);
        } catch (ApiException e) {
            return ApiResponse.error(e.statusCode(), e.getMessage());
        } catch (NotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ConflictException e) {
            return ApiResponse.error(409, e.getMessage());
        } catch (UnauthorizedException e) {
            return ApiResponse.error(401, e.getMessage());
        } catch (ForbiddenException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown by the model constructors when a field is invalid.
            return ApiResponse.error(400, e.getMessage());
        } catch (StorageException e) {
            e.printStackTrace();
            return ApiResponse.error(500, "Database error");
        } catch (RuntimeException e) {
            // A bug rather than a bad request; returning a response keeps it from failing the whole invocation.
            e.printStackTrace();
            return ApiResponse.error(500, "Internal error");
        }
    }

    private static void log(String route, ApiResponse response, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        System.out.println(route + " -> " + response.statusCode() + " (" + millis + " ms)");
    }

    /** Defers building the Spring context until the Lambda runtime creates the handler. */
    private static class SpringContextHolder {
        private static final AnnotationConfigApplicationContext CONTEXT =
                new AnnotationConfigApplicationContext(AppConfig.class);
    }
}
