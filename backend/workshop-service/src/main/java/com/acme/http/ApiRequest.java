package com.acme.http;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One HTTP request, read from a Lambda function URL event.
 *
 * @param segments path split on slashes, without the /api/{service} prefix that requests through CloudFront carry
 * @param query    query string parameters, such as archived=true
 * @param params   values of the {placeholders} in the matched route
 */
public record ApiRequest(String method, List<String> segments, Map<String, String> query,
                         Map<String, String> params, String body) {

    public static ApiRequest fromEvent(Map<String, Object> event) {
        Map<String, Object> request = event == null ? Map.of() : event;
        return new ApiRequest(method(request), segments(request), query(request), Map.of(), body(request));
    }

    public String path() {
        return "/" + String.join("/", segments);
    }

    /** A query string parameter, or null when it is absent. */
    public String query(String name) {
        return query.get(name);
    }

    public String param(String name) {
        return params.get(name);
    }

    public JsonObject json() {
        return Json.parseObject(body);
    }

    public <T> T body(Class<T> type) {
        return Json.convert(json(), type);
    }

    ApiRequest withParams(Map<String, String> routeParams) {
        return new ApiRequest(method, segments, query, routeParams, body);
    }

    /** Function URL events carry the method under requestContext.http; a direct invocation counts as GET. */
    private static String method(Map<String, Object> request) {
        if (request.get("requestContext") instanceof Map<?, ?> requestContext
                && requestContext.get("http") instanceof Map<?, ?> http
                && http.get("method") != null) {
            return http.get("method").toString().toUpperCase();
        }
        return "GET";
    }

    private static List<String> segments(Map<String, Object> request) {
        List<String> segments = Arrays.stream(readString(request, "rawPath").split("/"))
                .filter(segment -> !segment.isEmpty())
                .toList();
        return segments.size() >= 2 && segments.get(0).equals("api") ? segments.subList(2, segments.size()) : segments;
    }

    /** Function URL events carry the parsed query string; repeated keys arrive joined with commas. */
    private static Map<String, String> query(Map<String, Object> request) {
        Map<String, String> query = new HashMap<>();
        if (request.get("queryStringParameters") instanceof Map<?, ?> parameters) {
            parameters.forEach((key, value) -> query.put(String.valueOf(key), String.valueOf(value)));
        }
        return query;
    }

    private static String body(Map<String, Object> request) {
        String body = readString(request, "body");
        if (Boolean.TRUE.equals(request.get("isBase64Encoded"))) {
            return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return body;
    }

    private static String readString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? "" : value.toString();
    }
}
