package com.acme.http;

import java.util.HashMap;
import java.util.Map;

/** An HTTP response; a null body means no content. */
public record ApiResponse(int statusCode, Object body) {

    public static ApiResponse ok(Object body) {
        return new ApiResponse(200, body);
    }

    public static ApiResponse created(Object body) {
        return new ApiResponse(201, body);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, null);
    }

    /** Every error has the same shape: {"error": "..."}. */
    public static ApiResponse error(int statusCode, String message) {
        return new ApiResponse(statusCode, Map.of("error", String.valueOf(message)));
    }

    /** The map a Lambda function URL turns into the HTTP response. */
    public Map<String, Object> toLambdaResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", statusCode);
        if (body != null) {
            response.put("headers", Map.of("Content-Type", "application/json"));
            response.put("body", Json.write(body));
        }
        return response;
    }
}
