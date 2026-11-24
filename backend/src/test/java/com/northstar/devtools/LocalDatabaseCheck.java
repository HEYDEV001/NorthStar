package com.northstar.devtools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Whether the logins in .env.local work, asked before a gate tool (step 2.11) starts the app: the app's own failure
 * hides which one it was. A refusal names the settings and the database's code, never a value or the driver's message.
 */
final class LocalDatabaseCheck {

    private LocalDatabaseCheck() {}

    /** Logs in once as each of these users (the setting names of its name and password). */
    static void logins(LocalEnv env, String[]... users) {
        for (String[] user : users) login(env, user[0], user[1]);
    }

    private static void login(LocalEnv env, String userSetting, String passwordSetting) {
        Properties login = new Properties();
        login.setProperty("user", env.require(userSetting));
        login.setProperty("password", env.require(passwordSetting));
        login.setProperty("connectTimeout", "5");
        login.setProperty("loginTimeout", "5");
        login.setProperty("logServerErrorDetail", "false");
        try (Connection connection = DriverManager.getConnection(env.require(LocalEnv.DB_URL), login)) {
            connection.isValid(5);
        } catch (SQLException refused) {
            String state = refused.getSQLState() == null ? "none" : refused.getSQLState();
            if (state.startsWith("28")) {
                throw new LocalToolException(
                        "database_login_failed",
                        "The database did not accept " + userSetting + " and " + passwordSetting + " (SQLSTATE " + state
                                + "). Set the role's password and .env.local alike (db/bootstrap/README.md).");
            }
            throw new LocalToolException(
                    "database_unreachable",
                    "The database in .env.local did not answer (SQLSTATE " + state
                            + "). Is Supabase running (supabase start)?");
        }
    }
}
