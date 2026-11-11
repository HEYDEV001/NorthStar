package com.northstar.tenancy.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff invites (step 1.9). Create, list, revoke and resend are admin-only; accepting is membership-optional and runs
 * as the signed-in user, whose ID and email come only from the verified token.
 */
@RestController
class InviteController {

    private final Invites invites;
    private final InviteAcceptance acceptance;

    InviteController(Invites invites, InviteAcceptance acceptance) {
        this.invites = invites;
        this.acceptance = acceptance;
    }

    @PostMapping("/invites")
    ResponseEntity<InviteView> create(@Valid @RequestBody CreateInviteRequest request) {
        InviteView created = invites.create(request);
        return ResponseEntity.created(URI.create("/api/v1/invites")).body(created);
    }

    @GetMapping("/invites")
    List<InviteView> list() {
        return invites.list();
    }

    @PostMapping("/invites/{inviteId}/revoke")
    InviteView revoke(@PathVariable UUID inviteId) {
        return invites.revoke(inviteId);
    }

    @PostMapping("/invites/{inviteId}/resend")
    ResponseEntity<InviteView> resend(@PathVariable UUID inviteId) {
        InviteView resent = invites.resend(inviteId);
        return ResponseEntity.created(URI.create("/api/v1/invites")).body(resent);
    }

    @PostMapping("/invites/accept")
    AcceptedInviteResponse accept(@AuthenticationPrincipal Jwt token, @Valid @RequestBody AcceptInviteRequest request) {
        return acceptance.accept(UUID.fromString(token.getSubject()), token.getClaimAsString("email"), request.token());
    }
}
