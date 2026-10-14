package com.acme;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;

import java.util.Map;

/** GET /: answers without touching the database, so it shows whether the Lambda itself is up. */
public class StatusController implements Controller {
    static final String MESSAGE = "Hello from Spring Boot Lambda";

    @Override
    public void register(Router router) {
        router.get("/", Access.PUBLIC, request -> ApiResponse.ok(Map.of("message", MESSAGE)));
    }
}
