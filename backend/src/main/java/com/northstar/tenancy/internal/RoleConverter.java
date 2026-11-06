package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

/** Stores {@link Role} as the lowercase text the memberships.role check constraint allows. */
@Converter
class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role role) {
        return role == null ? null : role.databaseValue();
    }

    @Override
    public Role convertToEntityAttribute(String value) {
        if (value == null) return null;
        return Arrays.stream(Role.values())
                .filter(role -> role.databaseValue().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role in memberships.role"));
    }
}
