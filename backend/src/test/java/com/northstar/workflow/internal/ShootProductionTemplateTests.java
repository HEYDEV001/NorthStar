package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.NotFoundException;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.Workflows;
import com.northstar.workflow.Workflows.PublishedVersion;
import com.northstar.workflow.Workflows.WorkflowView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The default shoot_production template reaching businesses (step 3.2; workflow-spec.md sections 14 and 16), through the
 * app's own login with RLS: a workflow created from it starts with the current revision as its own draft and publishes
 * as it is; two businesses get independent copies (P9); one business's edit changes neither another's copy nor the
 * template; and a lead keeps the version it started on (W36).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShootProductionTemplateTests {

    private static final String SHOOT_PRODUCTION = "shoot_production";

    /** The template's current revision, as the app hands it out. */
    private static final String TEMPLATE = DefaultTemplates.read(SHOOT_PRODUCTION, 1);

    /** The business's own change: the Hot threshold (test data, not a setting). */
    private static final String EDITED = TEMPLATE.replace("\"hot\": 75", "\"hot\": 80");

    private final Workflows workflows;
    private final LeadIntake leads;
    private final DefaultTemplates templates;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    ShootProductionTemplateTests(
            @Autowired Workflows workflows,
            @Autowired LeadIntake leads,
            @Autowired DefaultTemplates templates,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.workflows = workflows;
        this.leads = leads;
        this.templates = templates;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void aShootProductionWorkflowStartsWithTheTemplateAsItsOwnDraft() {
        assertThat(EDITED).as("the edit changes the text").isNotEqualTo(TEMPLATE);
        TestData.Member owner = data.member("admin");

        WorkflowView created = asAdmin(owner, () -> workflows.create("Shoots", SHOOT_PRODUCTION));

        assertThat(created.template()).isEqualTo(SHOOT_PRODUCTION);
        assertThat(created.currentVersionId())
                .as("nothing is published until the admin publishes")
                .isNull();
        assertThat(sameJson(created.draftConfig(), TEMPLATE)).isTrue();
        assertThat(created.draftHash()).isEqualTo(ConfigJson.hash(created.draftConfig()));
        assertThat(asAdmin(owner, () -> workflows.get(created.id()))).isEqualTo(created);
        assertThat(asAdmin(owner, () -> workflows.validate(created.id(), created.draftConfig())))
                .as("the copy has no problems")
                .isEmpty();
    }

    @Test
    void theOtherTemplatesStartWithNoDraft() {
        for (String template : List.of("agency", "ac_home_service", "coaching", "blank")) {
            TestData.Member owner = data.member("admin");

            WorkflowView created = asAdmin(owner, () -> workflows.create("Flow", template));

            assertThat(created.draftConfig()).as(template).isNull();
            assertThat(created.draftHash()).as(template).isNull();
        }
    }

    @Test
    void theTemplatePublishesForANewBusiness() {
        TestData.Member owner = data.member("admin");
        WorkflowView created = asAdmin(owner, () -> workflows.create("Shoots", SHOOT_PRODUCTION));

        PublishedVersion first = asAdmin(owner, () -> workflows.publish(created.id(), created.draftHash()));

        assertThat(first.version()).isOne();
        assertThat(first.publishedBy()).as("the admin's own membership").isEqualTo(owner.membershipId());
        assertThat(sameJson(first.config(), stamped(TEMPLATE, created.id(), 1)))
                .as("the template, with the workflow ID and version written in")
                .isTrue();
        assertThat(publishedForNewLeads(owner.tenantId()))
                .as("new leads open on it")
                .contains(first.id());
    }

    @Test
    void theSystemActorPublishesTheTemplateForANewBusiness() {
        UUID tenant = data.tenant();

        PublishedVersion first = SystemScope.call(tenant, () -> {
            WorkflowView created = workflows.create("Shoots", SHOOT_PRODUCTION);
            return workflows.publish(created.id(), created.draftHash());
        });

        assertThat(first.version()).isOne();
        assertThat(first.publishedBy()).as("published by nobody").isNull();
        assertThat(sameJson(first.config(), stamped(TEMPLATE, first.workflowId(), 1)))
                .isTrue();
        assertThat(publishedForNewLeads(tenant)).contains(first.id());
    }

    @Test
    void P9_twoBusinessesGetIndependentCopiesOfTheTemplate() {
        TestData.Member mine = data.member("admin");
        TestData.Member theirs = data.member("admin");

        WorkflowView myCopy = asAdmin(mine, () -> workflows.create("Mine", SHOOT_PRODUCTION));
        WorkflowView theirCopy = asAdmin(theirs, () -> workflows.create("Theirs", SHOOT_PRODUCTION));

        assertThat(myCopy.id()).isNotEqualTo(theirCopy.id());
        assertThat(sameJson(myCopy.draftConfig(), TEMPLATE)).isTrue();
        assertThat(sameJson(theirCopy.draftConfig(), TEMPLATE)).isTrue();
        assertThat(asAdmin(mine, workflows::list)).extracting(WorkflowView::id).containsExactly(myCopy.id());
        List<Supplier<?>> attempts = List.of(
                () -> workflows.get(theirCopy.id()),
                () -> workflows.saveDraft(theirCopy.id(), EDITED),
                () -> workflows.publish(theirCopy.id(), theirCopy.draftHash()));
        for (Supplier<?> attempt : attempts) {
            assertThatThrownBy(() -> asAdmin(mine, attempt)).isInstanceOf(NotFoundException.class);
        }
        assertThat(asAdmin(theirs, () -> workflows.get(theirCopy.id()))).isEqualTo(theirCopy);
    }

    @Test
    void anEditToOneCopyLeavesTheOthersAndTheTemplateAlone() {
        TestData.Member editor = data.member("admin");
        TestData.Member other = data.member("admin");
        WorkflowView edited = asAdmin(editor, () -> workflows.create("Edited", SHOOT_PRODUCTION));
        WorkflowView untouched = asAdmin(other, () -> workflows.create("Untouched", SHOOT_PRODUCTION));

        PublishedVersion published = asAdmin(
                editor,
                () -> workflows.publish(
                        edited.id(), workflows.saveDraft(edited.id(), EDITED).draftHash()));

        assertThat(sameJson(published.config(), stamped(EDITED, edited.id(), 1)))
                .isTrue();
        assertThat(asAdmin(other, () -> workflows.get(untouched.id())))
                .as("the other business's copy is as it was, unpublished")
                .isEqualTo(untouched);
        assertThat(templates.current(SHOOT_PRODUCTION))
                .as("the template itself")
                .contains(TEMPLATE);
        TestData.Member later = data.member("admin");
        assertThat(sameJson(
                        asAdmin(later, () -> workflows.create("Later", SHOOT_PRODUCTION))
                                .draftConfig(),
                        TEMPLATE))
                .as("a business created afterwards gets the template, not the edit")
                .isTrue();
    }

    @Test
    void W36_aLeadOnTheTemplatesFirstVersionKeepsIt() {
        TestData.Member owner = data.member("admin");
        WorkflowView created = asAdmin(owner, () -> workflows.create("Shoots", SHOOT_PRODUCTION));
        PublishedVersion first = asAdmin(owner, () -> workflows.publish(created.id(), created.draftHash()));
        UUID lead = data.lead(owner.tenantId(), first.id(), null);

        PublishedVersion second = asAdmin(
                owner,
                () -> workflows.publish(
                        created.id(), workflows.saveDraft(created.id(), EDITED).draftHash()));

        assertThat(second.version()).isEqualTo(2);
        assertThat(publishedForNewLeads(owner.tenantId()))
                .as("new leads take version 2")
                .contains(second.id());
        assertThat(admin.sql("select workflow_version_id from northstar.leads where id = :id")
                        .param("id", lead)
                        .query(UUID.class)
                        .single())
                .as("the lead in progress keeps version 1")
                .isEqualTo(first.id());
        assertThat(asAdmin(owner, () -> workflows.publishedVersion(created.id(), 1)))
                .as("version 1 is the template as it was")
                .isEqualTo(first);
    }

    // Helpers.

    private <T> T asAdmin(TestData.Member member, Supplier<T> work) {
        return TenantScope.call(new TenantContext(member.tenantId(), member.userId(), Role.ADMIN), work);
    }

    private Optional<UUID> publishedForNewLeads(UUID tenant) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> leads.publishedWorkflowVersion()));
    }

    /** The config as publishing stores it: with the workflow's ID and the version number written in. */
    private String stamped(String config, UUID workflow, int version) {
        return admin.sql(
                        "select cast(:c as jsonb) || jsonb_build_object('workflow_id', cast(:w as text), 'version', :v)")
                .param("c", config)
                .param("w", workflow)
                .param("v", version)
                .query(String.class)
                .single();
    }

    private boolean sameJson(String actual, String expected) {
        return Boolean.TRUE.equals(admin.sql("select cast(:a as jsonb) = cast(:e as jsonb)")
                .param("a", actual)
                .param("e", expected)
                .query(Boolean.class)
                .single());
    }
}
