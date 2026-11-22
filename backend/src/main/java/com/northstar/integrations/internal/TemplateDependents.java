package com.northstar.integrations.internal;

import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateStatus;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.workflow.DependentActions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Brings a template's sends in line with its status (step 2.8c, W34; integration-specs.md section 2, Template status
 * from Meta), after every template the sync or a status webhook writes from Meta, in that write's transaction and after
 * the template's lock. It goes by the status the template then has, not by what changed, so a write that changes
 * nothing (an unchanged sync, an older webhook) still resumes a send blocked meanwhile, such as one Meta refused as
 * paused while the template here was still approved.
 *
 * <ul>
 *   <li>approved: sends blocked as {@value TemplateStatuses#TEMPLATE_PAUSED} resume, at the later of the time they were
 *       due and now;
 *   <li>paused: pending sends are blocked as {@value TemplateStatuses#TEMPLATE_PAUSED}, keeping their time; a send
 *       waiting for review stays waiting, and is blocked when it runs;
 *   <li>rejected, disabled, deleted or archived: pending, needs-review and paused sends fail with the template's code
 *       ({@link TemplateStatuses#sendRefusal}), their messages failed through the send's closing hook, and are listed on
 *       Failed Actions;
 *   <li>draft or submitted: nothing; a send fails as {@value TemplateStatuses#TEMPLATE_NOT_APPROVED} when it runs.
 * </ul>
 *
 * <p>The sends are found by their queued messages. Lock order: the template (the caller's), its queued messages'
 * contacts (shared, by ID), their actions, their messages; an opt-out locks the contact, then its actions, then their
 * messages, so the two never deadlock. Nothing here logs; the caller logs the result after its commit.
 */
@Component
class TemplateDependents {

    /** What the template's status did to its sends. */
    record Result(int blocked, int resumed, int failed) {
        static final Result NONE = new Result(0, 0, 0);

        boolean any() {
            return blocked + resumed + failed > 0;
        }
    }

    private final OutboundMessages messages;
    private final DependentActions actions;

    TemplateDependents(OutboundMessages messages, DependentActions actions) {
        this.messages = messages;
        this.actions = actions;
    }

    /** Runs in the caller's transaction, which has just written the template and holds its lock. */
    Result apply(UUID templateId, TemplateStatus status) {
        return switch (status) {
            case DRAFT, SUBMITTED -> Result.NONE;
            case APPROVED -> {
                DependentActions.Dependents sends = sends(templateId);
                yield sends == null
                        ? Result.NONE
                        : new Result(0, actions.unblock(sends, TemplateStatuses.TEMPLATE_PAUSED), 0);
            }
            case PAUSED -> {
                DependentActions.Dependents sends = sends(templateId);
                yield sends == null
                        ? Result.NONE
                        : new Result(actions.block(sends, TemplateStatuses.TEMPLATE_PAUSED), 0, 0);
            }
            case REJECTED, DISABLED, DELETED, ARCHIVED -> {
                DependentActions.Dependents sends = sends(templateId);
                String code = TemplateStatuses.sendRefusal(status).orElseThrow();
                yield sends == null ? Result.NONE : new Result(0, 0, actions.fail(sends, code));
            }
        };
    }

    /** The template's sends, by their queued messages (their contacts share-locked first); null if none is queued. */
    private DependentActions.Dependents sends(UUID templateId) {
        List<UUID> queued = messages.lockQueuedForTemplate(templateId);
        if (queued.isEmpty()) return null;
        Set<String> ids = queued.stream().map(UUID::toString).collect(Collectors.toUnmodifiableSet());
        return new DependentActions.Dependents(WhatsAppMessages.SEND_TYPE, WhatsAppSendHandler.PAYLOAD_MESSAGE_ID, ids);
    }
}
