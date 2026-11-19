package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.TestLogins;
import com.northstar.jobs.JobRunrPoolProbe;
import com.northstar.migrate.Migrate;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Database errors reach the logs without row values (CLAUDE.md: logs carry IDs and status codes only). Postgres puts
 * the values in an error's detail ("Key (column)=(value) already exists", "Failing row contains (...)"), and the driver
 * copies the detail into the exception's message unless logServerErrorDetail is off. Every pool turns it off: the
 * app's, JobRunr's, the worker's credential pool and the migration runner's.
 *
 * <p>Postgres already leaves the values out for a table with RLS, so a probe on a tenant table would pass whatever the
 * setting. Each probe therefore fails on a temporary table (no RLS, dropped at the end of the transaction), on a value
 * carrying a random marker; the failure is logged with its whole cause chain, as the app's error handler logs an
 * unexpected failure, and the marker must be nowhere in the captured output, while the SQL state and the primary
 * message are kept. The control shows the probe can fail: with the driver's default, the marker is logged.
 *
 * <p>Each pool's effective driver settings are checked too, prepareThreshold=0 included (Supabase's transaction pooler
 * cannot keep server-side prepared statements): Hikari passes these to the driver as Properties, which read only String
 * values, so a number or boolean would be silently ignored.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class DatabaseErrorDetailTests {

    private static final Logger log = LoggerFactory.getLogger(DatabaseErrorDetailTests.class);

    private static final String CREATE_PROBE =
            "create temporary table error_detail_probe (v text unique) on commit drop";
    private static final String INSERT_PROBE = "insert into error_detail_probe (v) values (?)";

    private final DataSource appDataSource;
    private final JdbcClient appJdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final PostgreSQLContainer postgres;
    private final TestLogins logins;
    private final TestData data;

    DatabaseErrorDetailTests(
            @Autowired DataSource appDataSource,
            @Autowired JdbcClient appJdbc,
            @Autowired EntityManager entityManager,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgreSQLContainer postgres,
            @Autowired TestLogins logins,
            @Autowired PostgresAdmin postgresAdmin) {
        this.appDataSource = appDataSource;
        this.appJdbc = appJdbc;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
        this.logins = logins;
        this.data = new TestData(postgresAdmin);
    }

    @Test
    void everyPoolTurnsOffErrorDetailAndTheHikariPoolsServerSidePrepares() throws SQLException {
        BaseConnection app = TenantScope.call(
                admin(data.tenant()),
                () -> transactions.execute(status -> {
                    try {
                        return DataSourceUtils.getConnection(appDataSource).unwrap(BaseConnection.class);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(app.getLogServerErrorDetail()).as("app pool").isFalse();
        assertThat(app.getPrepareThreshold()).as("app pool").isZero();

        try (HikariDataSource pool =
                        JobRunrPoolProbe.pool(postgres.getJdbcUrl(), "jobrunr_user", logins.jobRunrPassword());
                Connection connection = pool.getConnection()) {
            BaseConnection jobRunr = connection.unwrap(BaseConnection.class);
            assertThat(jobRunr.getLogServerErrorDetail()).as("JobRunr pool").isFalse();
            assertThat(jobRunr.getPrepareThreshold()).as("JobRunr pool").isZero();
        }
        try (HikariDataSource pool = CredentialsConfiguration.credentialsPool(
                        postgres.getJdbcUrl(), "secrets_user", logins.secretsUserPassword(), 1);
                Connection connection = pool.getConnection()) {
            BaseConnection credentials = connection.unwrap(BaseConnection.class);
            assertThat(credentials.getLogServerErrorDetail())
                    .as("credential pool")
                    .isFalse();
            assertThat(credentials.getPrepareThreshold()).as("credential pool").isZero();
        }
        try (Connection connection = Migrate.dataSource(postgres.getJdbcUrl(), "migrator", logins.migratorPassword())
                .getConnection()) {
            assertThat(connection.unwrap(BaseConnection.class).getLogServerErrorDetail())
                    .as("migration runner")
                    .isFalse();
        }
    }

    @Test
    void theAppPoolLogsAUniqueViolationWithoutTheValue(CapturedOutput output) {
        String marker = marker();
        Throwable failure = catchThrowable(() -> TenantScope.run(
                admin(data.tenant()),
                () -> transactions.executeWithoutResult(status -> {
                    appJdbc.sql(CREATE_PROBE).update();
                    appJdbc.sql(INSERT_PROBE).param(marker).update();
                    appJdbc.sql(INSERT_PROBE).param(marker).update();
                })));
        assertLoggedWithoutValue(failure, marker, output);
    }

    @Test
    void theAppPoolThroughHibernateLogsAUniqueViolationWithoutTheValue(CapturedOutput output) {
        String marker = marker();
        // Hibernate logs the SQL error itself (SqlExceptionHelper) before the exception reaches anyone.
        Throwable failure = catchThrowable(() -> TenantScope.run(
                admin(data.tenant()),
                () -> transactions.executeWithoutResult(status -> {
                    entityManager.createNativeQuery(CREATE_PROBE).executeUpdate();
                    for (int i = 0; i < 2; i++) {
                        entityManager
                                .createNativeQuery(INSERT_PROBE)
                                .setParameter(1, marker)
                                .executeUpdate();
                    }
                })));
        assertThat(output.getAll()).as("Hibernate logged the error").contains("SQLState: 23505");
        assertLoggedWithoutValue(failure, marker, output);
    }

    @Test
    void jobRunrsPoolLogsAUniqueViolationWithoutTheValue(CapturedOutput output) throws SQLException {
        String marker = marker();
        try (HikariDataSource pool =
                        JobRunrPoolProbe.pool(postgres.getJdbcUrl(), "jobrunr_user", logins.jobRunrPassword());
                Connection connection = pool.getConnection()) {
            assertLoggedWithoutValue(duplicateInTempTable(connection, marker), marker, output);
        }
    }

    @Test
    void theCredentialPoolLogsAUniqueViolationWithoutTheValue(CapturedOutput output) throws SQLException {
        String marker = marker();
        try (HikariDataSource pool = CredentialsConfiguration.credentialsPool(
                        postgres.getJdbcUrl(), "secrets_user", logins.secretsUserPassword(), 1);
                Connection connection = pool.getConnection()) {
            assertLoggedWithoutValue(duplicateInTempTable(connection, marker), marker, output);
        }
    }

    @Test
    void theMigrationRunnersConnectionLogsAUniqueViolationWithoutTheValue(CapturedOutput output) throws SQLException {
        String marker = marker();
        DataSource runner = Migrate.dataSource(postgres.getJdbcUrl(), "migrator", logins.migratorPassword());
        try (Connection connection = runner.getConnection()) {
            assertLoggedWithoutValue(duplicateInTempTable(connection, marker), marker, output);
        }
    }

    @Test
    void controlWithTheDriversDefaultTheValueWouldBeLogged(CapturedOutput output) throws SQLException {
        String marker = marker();
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), "migrator", logins.migratorPassword())) {
            Throwable failure = duplicateInTempTable(connection, marker);
            log.error("database error detail probe", failure);
        }
        assertThat(output.getAll())
                .as("with logServerErrorDetail on, the detail quotes the value: the probes above would see it")
                .contains("Key (v)=(" + marker + ") already exists");
    }

    // Helpers

    private void assertLoggedWithoutValue(Throwable failure, String marker, CapturedOutput output) {
        assertThat(failure).as("the statement failed").isNotNull();
        assertThat(sqlStateOf(failure)).isEqualTo("23505");
        log.error("database error detail probe", failure);
        assertThat(output.getAll())
                .as("the primary message is kept")
                .contains("duplicate key value violates unique constraint \"error_detail_probe_v_key\"")
                .as("no row value reaches the log")
                .doesNotContain(marker)
                .doesNotContain("already exists");
    }

    /** A duplicate on a temporary table, in one transaction that is rolled back (which drops the table). */
    private static Throwable duplicateInTempTable(Connection connection, String marker) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_PROBE);
            }
            insert(connection, marker);
            return catchThrowable(() -> insert(connection, marker));
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static void insert(Connection connection, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PROBE)) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private static TenantContext admin(UUID tenant) {
        return new TenantContext(tenant, UUID.randomUUID(), Role.ADMIN);
    }

    private static String sqlStateOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }

    private static String marker() {
        return "row-value-marker-" + UUID.randomUUID();
    }
}
