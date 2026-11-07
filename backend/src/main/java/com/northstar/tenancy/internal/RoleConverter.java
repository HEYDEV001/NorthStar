package com.northstar.tenancy.internal;

import com.northstar.tenancy.Role;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link Role} as the lowercase text the memberships.role check constraint allows. */
@Converter
class RoleConverter implements AttributeConverter<Role, String> {

    @Override
    public String convertToDatabaseColumn(Role role) {
        return role == null ? null : role.databaseValue();
    }

    @Override
    public Role convertToEntityAttribute(String value) {
        return value == null ? null : Role.fromDatabaseValue(value);
    }
}
