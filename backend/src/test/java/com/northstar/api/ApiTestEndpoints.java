package com.northstar.api;

import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.internal.MembershipRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints for the API conventions: one per error path, plus record lookups by ID for P9. Being a
 * @RestController, every path here is served under /api/v1.
 */
@RestController
class ApiTestEndpoints {

    private static final Logger log = LoggerFactory.getLogger(ApiTestEndpoints.class);

    /** A typical request body: amounts are integer paise. */
    record Payment(@NotBlank String name, @PositiveOrZero long amountPaise) {}

    private final MembershipRepository memberships;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    ApiTestEndpoints(MembershipRepository memberships, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    @PostMapping("/test/payments")
    Payment validated(@Valid @RequestBody Payment payment) {
        return payment;
    }

    @GetMapping("/test/count")
    int count(@RequestParam @Min(1) int count) {
        return count;
    }

    @GetMapping("/test/time")
    Map<String, Object> time() {
        return Map.of("at", Instant.parse("2026-11-07T16:30:00+05:30"), "amountPaise", 1_250_000L);
    }

    @GetMapping("/test/missing")
    void missing() {
        throw new NotFoundException("test_record", UUID.fromString("00000000-0000-0000-0000-00000000abcd"));
    }

    @GetMapping("/test/slug-taken")
    void slugTaken() {
        throw new ConflictException("slug_taken", "That slug is already taken");
    }

    @GetMapping("/test/stale")
    void stale() {
        throw new ObjectOptimisticLockingFailureException("northstar.leads secret-row", UUID.randomUUID());
    }

    @GetMapping("/test/boom")
    void boom() {
        throw new IllegalStateException("secret internal detail 4111-1111");
    }

    @GetMapping("/test/lost-context")
    int lostContext() {
        return CompletableFuture.supplyAsync(() -> memberships.findAll().size()).join();
    }

    @GetMapping("/test/log")
    String logSomething() {
        log.info("test log line from inside the tenant scope");
        return "logged";
    }

    /** Inserts a second membership for the same user in this tenant: a unique violation. */
    @PostMapping("/test/duplicate-membership")
    void duplicateMembership() {
        UUID user = TenantScope.require().userId();
        inTransaction("""
                insert into northstar.memberships (tenant_id, user_id, role, status)
                values (:tenant, :user, 'staff', 'invited')""", user);
    }

    /** A skill for a membership that does not exist here: a foreign key violation. */
    @PostMapping("/test/dangling-skill")
    void danglingSkill() {
        inTransaction("""
                insert into northstar.staff_skills (tenant_id, membership_id, skill)
                values (:tenant, :user, 'drone')""", UUID.randomUUID());
    }

    /** A membership with a role the check constraint does not allow. */
    @PostMapping("/test/bad-role")
    void badRole() {
        inTransaction("""
                insert into northstar.memberships (tenant_id, user_id, role, status)
                values (:tenant, :user, 'owner', 'active')""", TenantScope.require().userId());
    }

    @GetMapping("/test/memberships/{id}")
    UUID membershipByJpa(@PathVariable UUID id) {
        return memberships
                .findById(id)
                .orElseThrow(() -> new NotFoundException("membership", id))
                .getId();
    }

    @GetMapping("/test/memberships/{id}/native")
    UUID membershipByNativeQuery(@PathVariable UUID id) {
        return transactions.execute(
                status -> jdbc.sql("select id from northstar.memberships where id = :id and tenant_id = :tenant")
                        .param("id", id)
                        .param("tenant", TenantScope.require().tenantId())
                        .query(UUID.class)
                        .optional()
                        .orElseThrow(() -> new NotFoundException("membership", id)));
    }

    @PatchMapping("/test/memberships/{id}/native")
    void updateMembershipByNativeQuery(@PathVariable UUID id) {
        int updated = transactions.execute(status -> jdbc.sql("""
                        update northstar.memberships set whatsapp_phone = null
                        where id = :id and tenant_id = :tenant""")
                .param("id", id)
                .param("tenant", TenantScope.require().tenantId())
                .update());
        if (updated == 0) throw new NotFoundException("membership", id);
    }

    private void inTransaction(String sql, UUID second) {
        transactions.executeWithoutResult(status -> jdbc.sql(sql)
                .param("tenant", TenantScope.require().tenantId())
                .param("user", second)
                .update());
    }
}
