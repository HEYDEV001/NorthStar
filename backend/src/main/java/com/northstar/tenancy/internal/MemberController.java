package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The team and the member's own profile (step 1.8). Path IDs are typed UUIDs: anything else is a 400 before any lookup.
 */
@RestController
class MemberController {

    private final Members members;
    private final Profiles profiles;

    MemberController(Members members, Profiles profiles) {
        this.members = members;
        this.profiles = profiles;
    }

    @GetMapping("/members")
    List<MemberView> list() {
        return members.list();
    }

    @PatchMapping("/members/{membershipId}/role")
    MemberView changeRole(@PathVariable UUID membershipId, @Valid @RequestBody ChangeRoleRequest request) {
        return members.changeRole(membershipId, Role.fromDatabaseValue(request.role()));
    }

    @PostMapping("/members/{membershipId}/deactivate")
    MemberView deactivate(@PathVariable UUID membershipId) {
        return members.deactivate(membershipId);
    }

    @PostMapping("/members/{membershipId}/reactivate")
    MemberView reactivate(@PathVariable UUID membershipId) {
        return members.reactivate(membershipId);
    }

    @PutMapping("/me/profile")
    ProfileView updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return profiles.updateOwn(request);
    }
}
