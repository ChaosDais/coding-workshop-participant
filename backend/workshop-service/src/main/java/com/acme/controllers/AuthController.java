package com.acme.controllers;

import com.acme.http.Access;
import com.acme.http.ApiResponse;
import com.acme.http.Controller;
import com.acme.http.Router;
import com.acme.services.AuthService;

/**
 * Sign-in, refresh and sign-out are public. Viewing your own account and changing your password work even while a
 * password change is pending (the premade admin's first sign-in); editing your own name and email does not.
 */
public class AuthController implements Controller {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @Override
    public void register(Router router) {
        router.post("/auth/login", Access.PUBLIC, request -> ApiResponse.ok(
                        service.signIn(request.field("email"), request.field("password"))))
                .post("/auth/refresh", Access.PUBLIC, request -> ApiResponse.ok(
                        service.refresh(request.field("refreshToken"))))
                .post("/auth/logout", Access.PUBLIC, request -> {
                    service.signOut(request.field("refreshToken"));
                    return ApiResponse.noContent();
                })
                .get("/auth/me", Access.OWN_ACCOUNT, request -> ApiResponse.ok(request.user()))
                .put("/auth/me", Access.SIGNED_IN, request -> ApiResponse.ok(service.updateProfile(
                        request.user(), request.field("name"), request.field("email"))))
                .post("/auth/password", Access.OWN_ACCOUNT, request -> {
                    service.changePassword(request.user(), request.field("currentPassword"),
                            request.field("newPassword"));
                    return ApiResponse.noContent();
                });
    }
}
