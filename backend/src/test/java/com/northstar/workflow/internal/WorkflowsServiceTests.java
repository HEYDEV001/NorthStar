package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.ApiException;
import com.northstar.api.ConflictException;
import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.api.UnprocessableException;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ConfigProblem;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.WorkflowConfigInvalidException;
import com.northstar.workflow.Workflows;
import com.northstar.workflow.Workflows.DraftSaved;
import com.northstar.workflow.Workflows.PublishedVersion;
import com.northstar.workflow.Workflows.WorkflowView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workflow service (step 3.1; workflow-spec.md sections 15 and 16), through the app's own login with RLS: drafts
 * saved with warnings, publishing that refuses every problem, versions numbered n+1 with the workflow ID and version
 * written in, admins and the system actor only, one workflow per business, another business's workflow a 404 (P9), and
 * leads that keep their version (W36).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WorkflowsServiceTests {

    private static final String MINIMAL =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"}, {\"key\": \"needs_human\"}]}";

    /** The smallest config with one more stage, which the engine can reach (Lost). */
    private static final String WITH_LOST =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"},"
                    + " {\"key\": \"needs_human\"}, {\"key\": \"lost\"}]}";

    private final Workflows workflows;
    private final LeadIntake leads;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    WorkflowsServiceTests(
            @Autowired Workflows workflows,
            @Autowired LeadIntake leads,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.workflows = workflows;
        this.leads = leads;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A business with an admin and a staff member. */
    private record Business(UUID tenant, TestData.Member owner, UUID staffUser) {}

    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID staff = data.user();
        data.membership(owner.tenantId(), staff, "staff", "active");
        return new Business(owner.tenantId(), owner, staff);
    }

    @Test
    void anAdminCreatesSavesAndPublishesTheFirstVersion() {
        Business shop = business();

        WorkflowView created = asAdmin(shop, () -> workflows.create("  Test workflow  ", "blank"));
        assertThat(created.name()).as("stored without surrounding spaces").isEqualTo("Test workflow");
        assertThat(created.template()).isEqualTo("blank");
        assertThat(created.currentVersionId()).isNull();
        assertThat(created.draftConfig()).isNull();

        DraftSaved draft = asAdmin(shop, () -> workflows.saveDraft(created.id(), MINIMAL));
        assertThat(draft.warnings()).isEmpty();
        assertThat(draft.draftHash()).matches("[0-9a-f]{64}");

        PublishedVersion first = asAdmin(shop, () -> workflows.publish(created.id(), draft.draftHash()));

        assertThat(first.version()).isOne();
        assertThat(first.workflowId()).isEqualTo(created.id());
        assertThat(first.publishedBy())
                .as("the admin's own membership")
                .isEqualTo(shop.owner().membershipId());
        assertThat(sameJson(first.config(), TestData.minimalConfig(created.id(), 1)))
                .as("the draft, with the workflow ID and version written in")
                .isTrue();
        WorkflowView after = asAdmin(shop, () -> workflows.get(created.id()));
        assertThat(after.currentVersionId()).isEqualTo(first.id());
        assertThat(after.currentVersion()).isOne();
        assertThat(after.draftHash()).as("the draft stays for the next edit").isEqualTo(draft.draftHash());
        assertThat(asAdmin(shop, workflows::current)).contains(after);
        assertThat(asAdmin(shop, () -> workflows.publishedVersion(created.id(), 1)))
                .isEqualTo(first);
        assertThat(publishedForNewLeads(shop.tenant()))
                .as("new leads open on it")
                .contains(first.id());
        assertThat(audit(shop.tenant()))
                .containsExactly(
                        WorkflowsService.CREATED + "|workflows|" + created.id() + "|"
                                + shop.owner().userId(),
                        WorkflowsService.PUBLISHED + "|workflow_versions|" + first.id() + "|"
                                + shop.owner().userId());
    }

    @Test
    void draftsMayBeIncompleteButPublishingRefusesEveryProblem() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        String incomplete = "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\","
                + " \"exits\": [{\"to\": \"quoting\", \"on\": \"staff.action\"}]}]}";

        DraftSaved saved = asAdmin(shop, () -> workflows.saveDraft(workflow, incomplete));

        assertThat(saved.warnings())
                .containsExactly(
                        new ConfigProblem("$.stages", "stage_needs_human_missing"),
                        new ConfigProblem("$.stages[0].exits[0].to", "stage_unknown"));
        assertThat(asAdmin(shop, () -> workflows.get(workflow)).draftHash()).isEqualTo(saved.draftHash());
        assertThatThrownBy(() -> asAdmin(shop, () -> workflows.publish(workflow, saved.draftHash())))
                .isInstanceOfSatisfying(WorkflowConfigInvalidException.class, refused -> {
                    assertThat(refused.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(refused.code()).isEqualTo("workflow_config_invalid");
                    assertThat(refused.problems()).containsExactlyInAnyOrderElementsOf(saved.warnings());
                    assertThat(refused.details())
                            .isEqualTo(Map.of(
                                    "problems",
                                    refused.problems().stream()
                                            .map(p -> Map.of("path", p.path(), "code", p.code()))
                                            .toList()));
                });
        assertThat(versions(workflow)).as("nothing published").isZero();
        assertThat(asAdmin(shop, () -> workflows.get(workflow)).currentVersionId())
                .isNull();
    }

    @Test
    void aDraftThatCannotBeStoredIsRefusedAndTheStoredOneStays() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        String stored =
                asAdmin(shop, () -> workflows.saveDraft(workflow, MINIMAL)).draftHash();

        assertRefusedDraft(shop, workflow, "[1]", new ConfigProblem("$", "config_not_object"));
        assertRefusedDraft(shop, workflow, "{\"a\": 1, \"a\": 2}", new ConfigProblem("$.a", "json_member_duplicate"));
        assertRefusedDraft(shop, workflow, "not json", new ConfigProblem("$", "json_invalid"));
        assertRefusedDraft(
                shop, workflow, "{\"a\": \"" + "x".repeat(300_000) + "\"}", new ConfigProblem("$", "config_too_large"));

        assertThat(asAdmin(shop, () -> workflows.get(workflow)).draftHash()).isEqualTo(stored);
    }

    @Test
    void validatingStoresNothing() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();

        assertThat(asAdmin(shop, () -> workflows.validate(workflow, "{\"schema_version\": 1}")))
                .containsExactly(new ConfigProblem("$.stages", "field_required"));
        assertThat(asAdmin(shop, () -> workflows.validate(workflow, "[]")))
                .containsExactly(new ConfigProblem("$", "config_not_object"));
        assertThat(asAdmin(shop, () -> workflows.validate(workflow, MINIMAL))).isEmpty();
        assertThat(asAdmin(shop, () -> workflows.get(workflow)).draftConfig()).isNull();
    }

    @Test
    void publishingTakesTheDraftItsReviewerSaw() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        String reviewed =
                asAdmin(shop, () -> workflows.saveDraft(workflow, MINIMAL)).draftHash();
        String latest =
                asAdmin(shop, () -> workflows.saveDraft(workflow, WITH_LOST)).draftHash();

        assertConflict(() -> asAdmin(shop, () -> workflows.publish(workflow, reviewed)), "draft_changed");
        PublishedVersion published = asAdmin(shop, () -> workflows.publish(workflow, latest));

        assertThat(published.config()).contains("lost");
        assertThat(versions(workflow)).isOne();
    }

    @Test
    void withoutADraftThereIsNothingToPublish() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();

        assertConflict(() -> asAdmin(shop, () -> workflows.publish(workflow, "0".repeat(64))), "draft_missing");
    }

    @Test
    void W36_aLeadKeepsItsVersionAfterANewPublish() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        PublishedVersion first = publish(shop, workflow, MINIMAL);
        UUID lead = data.lead(shop.tenant(), first.id(), null);

        PublishedVersion second = publish(shop, workflow, WITH_LOST);

        assertThat(second.version()).isEqualTo(2);
        assertThat(sameJson(
                        second.config(),
                        WITH_LOST.replace(
                                "{\"schema_version\": 1,",
                                "{\"schema_version\": 1," + " \"workflow_id\": \"" + workflow + "\", \"version\": 2,")))
                .isTrue();
        assertThat(publishedForNewLeads(shop.tenant()))
                .as("new leads take version 2")
                .contains(second.id());
        assertThat(admin.sql("select workflow_version_id from northstar.leads where id = :id")
                        .param("id", lead)
                        .query(UUID.class)
                        .single())
                .as("the lead in progress keeps version 1")
                .isEqualTo(first.id());
        assertThat(asAdmin(shop, () -> workflows.publishedVersion(workflow, 1)))
                .as("version 1 is as it was")
                .isEqualTo(first);
    }

    @Test
    void staffNeverEditWorkflows() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        PublishedVersion published = publish(shop, workflow, MINIMAL);
        String draft = asAdmin(shop, () -> workflows.get(workflow)).draftHash();

        List<Supplier<?>> attempts = List.of(
                () -> workflows.create("Mine", "blank"),
                workflows::current,
                () -> workflows.get(workflow),
                () -> workflows.saveDraft(workflow, WITH_LOST),
                () -> workflows.validate(workflow, WITH_LOST),
                () -> workflows.publish(workflow, draft),
                () -> workflows.publishedVersion(workflow, 1));
        for (Supplier<?> attempt : attempts) {
            assertThatThrownBy(() -> asStaff(shop, attempt))
                    .isInstanceOfSatisfying(
                            ForbiddenException.class,
                            refused -> assertThat(refused.code()).isEqualTo("admin_only"));
        }

        assertThat(versions(workflow)).isOne();
        assertThat(asAdmin(shop, () -> workflows.get(workflow)).draftHash()).isEqualTo(draft);
        assertThat(asAdmin(shop, workflows::current).orElseThrow().currentVersionId())
                .isEqualTo(published.id());
    }

    @Test
    void theSystemActorPublishesAsNobody() {
        UUID tenant = data.tenant();

        PublishedVersion published = SystemScope.call(tenant, () -> {
            UUID workflow = workflows.create("Onboarding", "shoot_production").id();
            DraftSaved draft = workflows.saveDraft(workflow, MINIMAL);
            return workflows.publish(workflow, draft.draftHash());
        });

        assertThat(published.publishedBy()).isNull();
        assertThat(audit(tenant))
                .as("recorded as no user")
                .containsExactly(
                        WorkflowsService.CREATED + "|workflows|" + published.workflowId() + "|null",
                        WorkflowsService.PUBLISHED + "|workflow_versions|" + published.id() + "|null");
    }

    @Test
    void oneWorkflowPerBusiness() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();

        assertConflict(() -> asAdmin(shop, () -> workflows.create("Another", "agency")), "workflow_exists");

        // A second workflow made outside the service, published: ours cannot be published beside it.
        UUID outside = data.workflowVersion(shop.tenant());
        String draft =
                asAdmin(shop, () -> workflows.saveDraft(workflow, MINIMAL)).draftHash();
        assertConflict(() -> asAdmin(shop, () -> workflows.publish(workflow, draft)), "another_workflow_published");
        assertThat(versions(workflow)).isZero();
        assertThat(publishedForNewLeads(shop.tenant()))
                .as("the outside one is still the only one")
                .contains(outside);
    }

    @Test
    void twoAdminsCreatingAtOnceMakeOneWorkflow() throws Exception {
        Business shop = business();
        UUID secondAdmin = data.user();
        data.membership(shop.tenant(), secondAdmin, "admin", "active");
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> asAdmin(
                    shop,
                    () -> transactions.execute(status -> {
                        workflows.create("First", "blank");
                        created.countDown();
                        await(release);
                        return null;
                    })));
            assertThat(created.await(30, TimeUnit.SECONDS)).isTrue();
            Future<WorkflowView> second = threads.submit(() -> TenantScope.call(
                    new TenantContext(shop.tenant(), secondAdmin, Role.ADMIN),
                    () -> workflows.create("Second", "blank")));

            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .as("the second waits for the first to commit")
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ConflictException.class, e -> assertThat(e.code()).isEqualTo("workflow_exists"));
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThat(admin.sql("select name from northstar.workflows where tenant_id = :t")
                        .param("t", shop.tenant())
                        .query(String.class)
                        .list())
                .containsExactly("First");
    }

    @Test
    void P9_anotherBusinessesWorkflowIsNotFound() {
        Business mine = business();
        Business theirs = business();
        UUID workflow =
                asAdmin(theirs, () -> workflows.create("Theirs", "blank")).id();
        publish(theirs, workflow, MINIMAL);
        String draft = asAdmin(theirs, () -> workflows.get(workflow)).draftHash();

        List<Supplier<?>> attempts = List.of(
                () -> workflows.get(workflow),
                () -> workflows.saveDraft(workflow, WITH_LOST),
                () -> workflows.saveDraft(workflow, "[1]"), // a 404 before the draft's own problems
                () -> workflows.validate(workflow, MINIMAL),
                () -> workflows.publish(workflow, draft),
                () -> workflows.publishedVersion(workflow, 1));
        for (Supplier<?> attempt : attempts) {
            assertThatThrownBy(() -> asAdmin(mine, attempt)).isInstanceOf(NotFoundException.class);
        }
        assertThat(asAdmin(mine, workflows::current)).isEmpty();
        assertThat(asAdmin(theirs, () -> workflows.get(workflow)).draftHash()).isEqualTo(draft);
        assertThat(versions(workflow)).isOne();
    }

    @Test
    void P10_withoutATenantScopeNothingRuns() {
        assertThatThrownBy(() -> workflows.create("Test workflow", "blank"))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> workflows.validate(UUID.randomUUID(), MINIMAL))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void aNewWorkflowHasANameAndAnIndustryTemplate() {
        Business shop = business();

        assertUnprocessable(() -> asAdmin(shop, () -> workflows.create("   ", "blank")), "workflow_name_invalid");
        assertUnprocessable(
                () -> asAdmin(shop, () -> workflows.create("x".repeat(101), "blank")), "workflow_name_invalid");
        assertUnprocessable(() -> asAdmin(shop, () -> workflows.create(null, "blank")), "workflow_name_invalid");
        assertUnprocessable(() -> asAdmin(shop, () -> workflows.create("Fine", "dev_seed")), "template_unknown");
        assertUnprocessable(() -> asAdmin(shop, () -> workflows.create("Fine", null)), "template_unknown");
        assertThat(asAdmin(shop, workflows::current)).isEmpty();

        assertThat(asAdmin(shop, () -> workflows.create("x".repeat(100), "coaching"))
                        .name())
                .hasSize(100);
    }

    @Test
    void aConfigNamesOnlyItsWorkflowsOwnTemplate() {
        Business shop = business();
        UUID workflow =
                asAdmin(shop, () -> workflows.create("Test workflow", "blank")).id();
        String agency = MINIMAL.replace("{\"schema_version\": 1,", "{\"schema_version\": 1, \"template\": \"agency\",");

        assertThat(asAdmin(shop, () -> workflows.saveDraft(workflow, agency)).warnings())
                .containsExactly(new ConfigProblem("$.template", "value_invalid"));
        assertThat(asAdmin(shop, () -> workflows.saveDraft(workflow, agency.replace("agency", "blank")))
                        .warnings())
                .isEmpty();
    }

    // Helpers.

    private PublishedVersion publish(Business shop, UUID workflow, String config) {
        return asAdmin(
                shop,
                () -> workflows.publish(
                        workflow, workflows.saveDraft(workflow, config).draftHash()));
    }

    private <T> T asAdmin(Business shop, Supplier<T> work) {
        return TenantScope.call(new TenantContext(shop.tenant(), shop.owner().userId(), Role.ADMIN), work);
    }

    private <T> T asStaff(Business shop, Supplier<T> work) {
        return TenantScope.call(new TenantContext(shop.tenant(), shop.staffUser(), Role.STAFF), work);
    }

    private Optional<UUID> publishedForNewLeads(UUID tenant) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> leads.publishedWorkflowVersion()));
    }

    private long versions(UUID workflow) {
        return admin.sql("select count(*) from northstar.workflow_versions where workflow_id = :w")
                .param("w", workflow)
                .query(Long.class)
                .single();
    }

    private List<String> audit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|' || coalesce(actor_user_id::text, 'null')
                        from northstar.audit_log where tenant_id = :t and action like 'workflow.%' order by created_at, id""").param("t", tenant).query(String.class).list();
    }

    private boolean sameJson(String actual, String expected) {
        return Boolean.TRUE.equals(admin.sql("select cast(:a as jsonb) = cast(:e as jsonb)")
                .param("a", actual)
                .param("e", expected)
                .query(Boolean.class)
                .single());
    }

    private void assertRefusedDraft(Business shop, UUID workflow, String draft, ConfigProblem problem) {
        assertThatThrownBy(() -> asAdmin(shop, () -> workflows.saveDraft(workflow, draft)))
                .isInstanceOfSatisfying(
                        WorkflowConfigInvalidException.class,
                        refused -> assertThat(refused.problems()).containsExactly(problem));
    }

    private static void assertConflict(Runnable work, String code) {
        assertCode(work, ConflictException.class, code);
    }

    private static void assertUnprocessable(Runnable work, String code) {
        assertCode(work, UnprocessableException.class, code);
    }

    private static void assertCode(Runnable work, Class<? extends ApiException> type, String code) {
        assertThatThrownBy(work::run)
                .isInstanceOfSatisfying(type, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
