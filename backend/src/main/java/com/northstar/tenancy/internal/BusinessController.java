package com.northstar.tenancy.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user and their business (step 1.7). Both endpoints are membership-optional: a user without an active
 * membership may create a business and may ask who they are. The user ID is always the verified token's sub.
 */
@RestController
class BusinessController {

    private final BusinessCreation creation;
    private final CurrentUser currentUser;

    BusinessController(BusinessCreation creation, CurrentUser currentUser) {
        this.creation = creation;
        this.currentUser = currentUser;
    }

    @PostMapping("/businesses")
    ResponseEntity<CreatedBusinessResponse> create(
            @AuthenticationPrincipal Jwt token, @Valid @RequestBody CreateBusinessRequest request) {
        CreatedBusinessResponse created = creation.create(userId(token), request);
        return ResponseEntity.created(URI.create("/api/v1/me")).body(created);
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt token) {
        return currentUser.describe(userId(token), token.getClaimAsString("email"));
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject()); // a UUID: checked when the token was decoded
    }
}
