package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The P14 trigger itself, below the API: northstar.keep_an_active_admin() serialises admin-reducing changes per tenant
 * with an advisory lock and refuses any change that would leave no active admin, whatever code path makes it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActiveAdminTriggerTests {

    private final AppUserDatabase app;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    ActiveAdminTriggerTests(
            @Autowired AppUserDatabase app,
            @Autowired PostgresAdmin postgres,
            @Autowired JdbcClient jdbc,
            @Autowired PlatformTransactionManager transactionManager) {
        this.app = app;
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    private record TwoAdmins(UUID tenant, UUID userA, UUID membershipA, UUID userB, UUID membershipB) {}

    private TwoAdmins twoAdmins() {
        TestData.Member a = data.member("admin");
        UUID userB = data.user();
        UUID membershipB = data.membership(a.tenantId(), userB, "admin", "active");
        return new TwoAdmins(a.tenantId(), a.userId(), a.membershipId(), userB, membershipB);
    }

    @Test
    void P14_secondConcurrentDemotionWaitsForTheFirstAndIsRefused() throws Exception {
        TwoAdmins admins = twoAdmins();
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> app.run(admins.tenant(), admins.userA(), "admin", jdbc -> {
                demote(jdbc, admins.membershipB());
                firstUpdated.countDown();
                await(secondStarted);
                sleep(Duration.ofMillis(400)); // keep the lock while the second transaction tries
            }));
            Future<Long> second = pool.submit(() -> {
                await(firstUpdated);
                long started = System.nanoTime();
                try {
                    app.run(admins.tenant(), admins.userB(), "admin", jdbc -> {
                        secondStarted.countDown();
                        demote(jdbc, admins.membershipA());
                    });
                    return -1L;
                } catch (RuntimeException refused) {
                    assertThat(DatabaseErrors.sqlState(refused)).isEqualTo("NSA01");
                    return Duration.ofNanos(System.nanoTime() - started).toMillis();
                }
            });

            first.get(30, TimeUnit.SECONDS);
            long waitedMillis = second.get(30, TimeUnit.SECONDS);
            assertThat(waitedMillis)
                    .as("refused after waiting for the first commit")
                    .isGreaterThanOrEqualTo(300);
        }
        assertThat(activeAdmins(admins.tenant())).isOne();
    }

    @Test
    void P14_ruleHoldsWhateverTheCodePath() {
        TestData.Member only = data.member("admin");

        assertThatThrownBy(() ->
                        app.run(only.tenantId(), only.userId(), "admin", jdbc -> demote(jdbc, only.membershipId())))
                .satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isEqualTo("NSA01"));
        assertThatThrownBy(() -> admin.sql("update northstar.memberships set status = 'deactivated' where id = :id")
                        .param("id", only.membershipId())
                        .update())
                .satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isEqualTo("NSA01"));
        assertThatThrownBy(() -> admin.sql("delete from northstar.memberships where id = :id")
                        .param("id", only.membershipId())
                        .update())
                .satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isEqualTo("NSA01"));
        assertThat(activeAdmins(only.tenantId())).isOne();
    }

    @Test
    void P14_triggerRefusesRepeatableRead() {
        TwoAdmins admins = twoAdmins();

        assertThatThrownBy(() -> postgres.rolledBack(probe -> {
                    probe.sql("set transaction isolation level repeatable read").update();
                    demote(probe, admins.membershipB());
                }))
                .satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isEqualTo("NSA02"));
        assertThat(activeAdmins(admins.tenant())).isEqualTo(2);
    }

    @Test
    void appTransactionsRunAtReadCommitted() {
        TestData.Member member = data.member("admin");
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        String isolation = TenantScope.call(
                new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                () -> transactions.execute(status -> jdbc.sql("show transaction_isolation")
                        .query(String.class)
                        .single()));

        assertThat(isolation).isEqualTo("read committed");
    }

    @Test
    void changingAnAdminsPhoneDoesNotWaitForTheAdminLock() throws Exception {
        TwoAdmins admins = twoAdmins();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch phoneChanged = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> app.run(admins.tenant(), admins.userA(), "admin", jdbc -> {
                demote(jdbc, admins.membershipB()); // takes the tenant's admin lock
                lockHeld.countDown();
                await(phoneChanged, Duration.ofSeconds(10));
            }));
            await(lockHeld);
            Future<?> phone = pool.submit(() -> app.run(
                    admins.tenant(),
                    admins.userB(),
                    "admin",
                    jdbc -> jdbc.sql("update northstar.memberships set whatsapp_phone = '+919800000001' where id = :id")
                            .param("id", admins.membershipA())
                            .update()));

            phone.get(3, TimeUnit.SECONDS); // would time out if it waited for the lock
            phoneChanged.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    private static void demote(JdbcClient jdbc, UUID membership) {
        jdbc.sql("update northstar.memberships set role = 'staff' where id = :id")
                .param("id", membership)
                .update();
    }

    private int activeAdmins(UUID tenant) {
        return admin.sql("""
                        select count(*) from northstar.memberships
                        where tenant_id = :tenant and role = 'admin' and status = 'active'""").param("tenant", tenant).query(Integer.class).single();
    }

    private static void await(CountDownLatch latch) {
        await(latch, Duration.ofSeconds(20));
    }

    private static void await(CountDownLatch latch, Duration timeout) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
