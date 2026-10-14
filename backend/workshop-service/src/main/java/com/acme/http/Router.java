package com.acme.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Matches a request's method and path against routes such as "/projects/{id}". */
public class Router {
    /** Handles one matched request. */
    @FunctionalInterface
    public interface Route {
        ApiResponse handle(ApiRequest request);
    }

    private record Entry(String method, List<String> pattern, Route route) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public Router get(String path, Route route) {
        return add("GET", path, route);
    }

    public Router post(String path, Route route) {
        return add("POST", path, route);
    }

    public Router put(String path, Route route) {
        return add("PUT", path, route);
    }

    public Router delete(String path, Route route) {
        return add("DELETE", path, route);
    }

    public ApiResponse dispatch(ApiRequest request) {
        for (Entry entry : entries) {
            Map<String, String> params = match(entry.pattern(), request.segments());
            if (params != null && entry.method().equals(request.method())) {
                return entry.route().handle(request.withParams(params));
            }
        }
        throw new ApiException(404, "No route for " + request.method() + " " + request.path());
    }

    private Router add(String method, String path, Route route) {
        List<String> pattern = Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).toList();
        entries.add(new Entry(method, pattern, route));
        return this;
    }

    /** The placeholder values when the path fits the pattern, otherwise null. */
    private static Map<String, String> match(List<String> pattern, List<String> segments) {
        if (pattern.size() != segments.size()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int index = 0; index < pattern.size(); index++) {
            String expected = pattern.get(index);
            if (expected.startsWith("{") && expected.endsWith("}")) {
                params.put(expected.substring(1, expected.length() - 1), segments.get(index));
            } else if (!expected.equals(segments.get(index))) {
                return null;
            }
        }
        return params;
    }
}
