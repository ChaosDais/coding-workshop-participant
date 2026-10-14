package com.acme;

import com.acme.config.AppConfig;
import com.acme.errors.ConflictException;
import com.acme.errors.NotFoundException;
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
        return respond(event).toLambdaResponse();
    }

    private ApiResponse respond(Map<String, Object> event) {
        try {
            return router.dispatch(ApiRequest.fromEvent(event));
        } catch (ApiException e) {
            return ApiResponse.error(e.statusCode(), e.getMessage());
        } catch (NotFoundException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (ConflictException e) {
            return ApiResponse.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown by the model constructors when a field is invalid.
            return ApiResponse.error(400, e.getMessage());
        } catch (StorageException e) {
            e.printStackTrace();
            return ApiResponse.error(500, "Database error");
        }
    }

    /** Defers building the Spring context until the Lambda runtime creates the handler. */
    private static class SpringContextHolder {
        private static final AnnotationConfigApplicationContext CONTEXT =
                new AnnotationConfigApplicationContext(AppConfig.class);
    }
}
