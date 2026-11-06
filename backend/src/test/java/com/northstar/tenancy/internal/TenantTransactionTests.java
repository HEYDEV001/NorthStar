package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.jdbc.PgConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every transaction runs with the tenant context set on its own connection, and nothing touches the database without
 * one (data-model.md section 12, P10). All access here goes through the app's real app_user datasource; fixtures are
 * written through {@link PostgresAdmin}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantTransactionTests {

    private static final String CURRENT_CONTEXT = "select coalesce(current_setting('app.tenant_id', true), '') || ','"
            + " || coalesce(current_setting('app.user_id', true), '') || ','"
            + " || coalesce(current_setting('app.role', true), '')";

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnlyTransactions;
    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final JdbcClient admin;

    TenantTransactionTests(
            @Autowired JdbcClient jdbc,
            @Autowired DataSource dataSource,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired TenantRepository tenants,
            @Autowired MembershipRepository memberships,
            @Autowired PostgresAdmin postgres) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions = new TransactionTemplate(transactionManager);
        this.readOnlyTransactions.setReadOnly(true);
        this.tenants = tenants;
        this.memberships = memberships;
        this.admin = postgres.jdbc();
    }

    @Test
    void P10_queryWithoutTenantContextFailsBeforeRunning() {
        UUID user = user();
        membership(tenant(), user, "active");

        assertThatThrownBy(memberships::findAll).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> memberships.findByUserId(user)).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> jdbc.sql("select count(*) from northstar.memberships")
                        .query(Integer.class)
                        .single())
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> transactions.execute(status -> 1)).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> readOnlyTransactions.execute(status -> 1))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void P10_aScopeWithoutATransactionStillFailsBeforeRunning() {
        TenantContext context = new TenantContext(tenant(), user(), Role.STAFF);
        assertThatThrownBy(() -> TenantScope.run(
                        context, () -> jdbc.sql("select 1").query(Integer.class).single()))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void contextIsSetOnTheTransactionConnection() {
        TenantContext context = new TenantContext(tenant(), user(), Role.ADMIN);
        String expected = context.tenantId() + "," + context.userId() + ",admin";

        String readWrite = TenantScope.call(context, () -> transactions.execute(status -> currentContext()));
        String readOnly = TenantScope.call(
                context,
                () -> readOnlyTransactions.execute(status -> currentContext() + ","
                        + jdbc.sql("select current_setting('transaction_read_only')")
                                .query(String.class)
                                .single()));

        assertThat(readWrite).isEqualTo(expected);
        assertThat(readOnly).isEqualTo(expected + ",on");
    }

    @Test
    void concurrentTenantsNeverSeeEachOther() throws Exception {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        membership(tenantA, user(), "active");
        membership(tenantB, user(), "active");
        CountDownLatch bothOpen = new CountDownLatch(2);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<UUID>> seenByA = pool.submit(() -> tenantsVisibleTo(tenantA, bothOpen));
            Future<List<UUID>> seenByB = pool.submit(() -> tenantsVisibleTo(tenantB, bothOpen));

            assertThat(seenByA.get(30, TimeUnit.SECONDS)).containsOnly(tenantA).hasSize(4);
            assertThat(seenByB.get(30, TimeUnit.SECONDS)).containsOnly(tenantB).hasSize(4);
        }
    }

    /** Holds a transaction open until the other tenant's is open too, then reads through every path. */
    private List<UUID> tenantsVisibleTo(UUID tenant, CountDownLatch bothOpen) {
        return TenantScope.call(
                new TenantContext(tenant, user(), Role.STAFF),
                () -> transactions.execute(status -> {
                    List<UUID> seen = new ArrayList<>();
                    seen.add(UUID.fromString(currentContext().split(",")[0]));
                    bothOpen.countDown();
                    await(bothOpen);
                    memberships.findAll().forEach(m -> seen.add(m.getTenantId()));
                    seen.addAll(jdbc.sql("select tenant_id from northstar.memberships")
                            .query(UUID.class)
                            .list());
                    seen.addAll(jdbc.sql("select id from northstar.tenants")
                            .query(UUID.class)
                            .list());
                    return seen;
                }));
    }

    @Test
    void pooledConnectionCarriesNoLeftoverContext() throws SQLException {
        for (UUID tenant : List.of(tenant(), tenant())) {
            TenantScope.run(
                    new TenantContext(tenant, user(), Role.ADMIN),
                    () -> transactions.execute(status -> currentContext()));
        }

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int total = pool.getHikariPoolMXBean().getTotalConnections();
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) borrowed.add(pool.getConnection());
            for (Connection connection : borrowed) {
                try (Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery(CURRENT_CONTEXT)) {
                    result.next();
                    assertThat(result.getString(1)).isEqualTo(",,");
                }
            }
        } finally {
            for (Connection connection : borrowed) connection.close();
        }

        try (Connection guarded = dataSource.getConnection()) {
            assertThatThrownBy(guarded::createStatement).isInstanceOf(MissingTenantContextException.class);
        }
    }

    @Test
    void tenantIdIsFilledFromTheContextAndAMismatchIsRejected() {
        UUID tenant = tenant();
        UUID otherTenant = tenant();
        UUID member = user();
        TenantContext context = new TenantContext(tenant, user(), Role.ADMIN);

        UUID saved = TenantScope.call(
                context,
                () -> transactions.execute(status -> memberships
                        .save(new Membership(member, Role.STAFF, "active"))
                        .getId()));
        assertThat(membershipTenant(saved)).isEqualTo(tenant);

        Membership forged = new Membership(user(), Role.STAFF, "invited");
        ReflectionTestUtils.setField(forged, "tenantId", otherTenant);
        assertThatThrownBy(() -> TenantScope.run(
                        context, () -> transactions.execute(status -> memberships.saveAndFlush(forged))))
                .hasMessageContaining("tenant");
        assertThat(admin.sql("select count(*) from northstar.memberships where tenant_id = :t")
                        .param("t", otherTenant)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void tenantEntityWritesOnlyColumnsAppUserMayWrite() {
        UUID id = UUID.randomUUID();
        TenantContext context = new TenantContext(id, user(), Role.ADMIN);

        Tenant created = TenantScope.call(
                context,
                () -> transactions.execute(status -> tenants.save(Tenant.create(id, "New business", "slug-" + id))));
        Tenant renamed = TenantScope.call(
                context,
                () -> transactions.execute(status -> {
                    Tenant tenant = tenants.findById(id).orElseThrow();
                    tenant.rename("Renamed business");
                    return tenants.saveAndFlush(tenant);
                }));

        assertThat(created.getStatus()).isEqualTo("onboarding");
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(renamed.getName()).isEqualTo("Renamed business");
        assertThat(renamed.getUpdatedAt()).isNotNull();
        assertThat(admin.sql(
                                "select timezone || ',' || approval_mode || ',' || status from northstar.tenants where id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("Asia/Kolkata,true,onboarding");
    }

    @Test
    void appConnectsAsAppUserWithPrepareThresholdZero() {
        TenantContext context = new TenantContext(tenant(), user(), Role.STAFF);

        String connection = TenantScope.call(
                context,
                () -> transactions.execute(status -> {
                    String user =
                            jdbc.sql("select current_user").query(String.class).single();
                    try {
                        int threshold = DataSourceUtils.getConnection(dataSource)
                                .unwrap(PGConnection.class)
                                .getPrepareThreshold();
                        return user + "," + threshold;
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }));

        assertThat(connection).isEqualTo("app_user,0");
    }

    @Test
    void unwrapToTheDriverConnectionOutsideATenantTransactionIsRefused() throws SQLException {
        try (Connection guarded = dataSource.getConnection()) {
            assertThatThrownBy(() -> guarded.unwrap(PgConnection.class))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> guarded.unwrap(PGConnection.class))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> guarded.isWrapperFor(PgConnection.class))
                    .isInstanceOf(MissingTenantContextException.class);

            assertThat(guarded.unwrap(Connection.class)).isSameAs(guarded);
            assertThat(guarded.isWrapperFor(Connection.class)).isTrue();
        }
    }

    @Test
    void unwrapIsRefusedAgainOnceTheTenantTransactionEnds() {
        AtomicReference<Connection> used = new AtomicReference<>();
        TenantContext context = new TenantContext(tenant(), user(), Role.STAFF);

        PgConnection inside = TenantScope.call(
                context,
                () -> transactions.execute(status -> {
                    Connection connection = DataSourceUtils.getConnection(dataSource);
                    used.set(connection);
                    try {
                        return connection.unwrap(PgConnection.class);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }));

        assertThat(inside).isNotNull();
        assertThatThrownBy(() -> used.get().unwrap(PgConnection.class))
                .isInstanceOf(MissingTenantContextException.class);
    }

    private String currentContext() {
        return jdbc.sql(CURRENT_CONTEXT).query(String.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Other transaction never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // Fixtures, written as postgres (bypasses RLS).

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", id)
                .param("slug", "slug-" + id)
                .update();
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.user_profiles (id, full_name) values (:id, 'Test user')")
                .param("id", id)
                .update();
        return id;
    }

    private void membership(UUID tenant, UUID user, String status) {
        admin.sql("""
                        insert into northstar.memberships (tenant_id, user_id, role, status)
                        values (:tenant, :user, 'staff', :status)""")
                .param("tenant", tenant)
                .param("user", user)
                .param("status", status)
                .update();
    }

    private UUID membershipTenant(UUID membership) {
        return admin.sql("select tenant_id from northstar.memberships where id = :id")
                .param("id", membership)
                .query(UUID.class)
                .single();
    }
}
