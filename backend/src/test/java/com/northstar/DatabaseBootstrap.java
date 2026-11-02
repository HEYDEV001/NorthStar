package com.northstar;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies db/bootstrap/roles.sql to a test database the way an operator does per environment. */
final class DatabaseBootstrap {

    static final Path ROLES_SCRIPT = Path.of("../db/bootstrap/roles.sql");

    private DatabaseBootstrap() {}

    static void applyRoles(Connection postgres) throws SQLException {
        try (Statement statement = postgres.createStatement()) {
            statement.execute(Files.readString(ROLES_SCRIPT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Test containers only: a throwaway password per run. Real environments set passwords with psql's \password. */
    static void enableLogin(Connection postgres, String role, String password) throws SQLException {
        try (Statement statement = postgres.createStatement()) {
            statement.execute("alter role " + role + " login password '" + password + "'");
        }
    }
}
