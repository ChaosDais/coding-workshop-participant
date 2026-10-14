package com.acme.http;

import com.acme.errors.ForbiddenException;
import com.acme.models.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches a request's method and path against routes such as "/projects/{id}", then checks the route's
 * {@link Access} before running it: 401 when sign-in is required and missing or invalid, 403 for the wrong role.
 */
public class Router {
    /** Handles one matched request. */
    @FunctionalInterface
    public interface Route {
        ApiResponse handle(ApiRequest request);
    }

    private record Entry(String method, List<String> pattern, Access access, Route route) {
    }

    private final Authenticator authenticator;
    private final List<Entry> entries = new ArrayList<>();

    public Router(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    public Router get(String path, Access access, Route route) {
        return add("GET", path, access, route);
    }

    public Router post(String path, Access access, Route route) {
        return add("POST", path, access, route);
    }

    public Router put(String path, Access access, Route route) {
        return add("PUT", path, access, route);
    }

    public Router delete(String path, Access access, Route route) {
        return add("DELETE", path, access, route);
    }

    public ApiResponse dispatch(ApiRequest request) {
        for (Entry entry : entries) {
            Map<String, String> params = match(entry.pattern(), request.segments());
            if (params != null && entry.method().equals(request.method())) {
                ApiRequest matched = request.withParams(params);
                if (entry.access().signInRequired()) {
                    User user = authenticator.authenticate(request.header("Authorization"));
                    if (!entry.access().allows(user.role())) {
                        throw new ForbiddenException("Access denied");
                    }
                    matched = matched.withUser(user);
                }
                return entry.route().handle(matched);
            }
        }
        throw new ApiException(404, "No route for " + request.method() + " " + request.path());
    }

    private Router add(String method, String path, Access access, Route route) {
        List<String> pattern = Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).toList();
        entries.add(new Entry(method, pattern, access, route));
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
