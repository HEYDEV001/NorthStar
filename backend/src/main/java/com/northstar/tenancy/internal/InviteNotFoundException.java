package com.northstar.tenancy.internal;

import com.northstar.api.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * No invite matches this link's token. Not a {@link com.northstar.api.NotFoundException}: the token is a secret, not a
 * UUID, and must never be logged, so nothing about it is recorded.
 */
final class InviteNotFoundException extends ApiException {

    InviteNotFoundException() {
        super(HttpStatus.NOT_FOUND, "invite_not_found", "This invite link is not valid", Map.of());
    }
}
