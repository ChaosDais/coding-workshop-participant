package com.acme.auth;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;

/**
 * POST /auth/setup creates the first admin while no users exist; sign-in, refresh and sign-out are public;
 * viewing and editing your own name and email (/auth/me) and changing your password need any signed-in user.
 */
public class AuthController implements Controller {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.post("/auth/setup", Access.PUBLIC, request -> ApiResponse.created(service.setUpFirstAdmin(
                        request.field("email"), request.field("name"), request.field("password"))))
                .post("/auth/login", Access.PUBLIC, request -> ApiResponse.ok(
                        service.signIn(request.field("email"), request.field("password"))))
                .post("/auth/refresh", Access.PUBLIC, request -> ApiResponse.ok(
                        service.refresh(request.field("refreshToken"))))
                .post("/auth/logout", Access.PUBLIC, request -> {
                    service.signOut(request.field("refreshToken"));
                    return ApiResponse.noContent();
                })
                .get("/auth/me", Access.SIGNED_IN, request -> ApiResponse.ok(request.user()))
                .put("/auth/me", Access.SIGNED_IN, request -> ApiResponse.ok(service.updateProfile(
                        request.user(), request.field("name"), request.field("email"))))
                .post("/auth/password", Access.SIGNED_IN, request -> {
                    service.changePassword(request.user(), request.field("currentPassword"),
                            request.field("newPassword"));
                    return ApiResponse.noContent();
                });
    }
}
