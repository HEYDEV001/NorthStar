package com.northstar.crm.internal;

import com.northstar.contacts.ManualContacts.ContactView;
import com.northstar.workflow.LeadEntry.LeadView;
import com.northstar.workflow.Timeline;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON shapes of the leads API (steps 2.10 and 3.3b). Every field is always present, null when it has no value. toString
 * shows IDs and codes only, never the contact's phone, name or email.
 */
final class LeadResponses {

    private LeadResponses() {}

    /**
     * A lead and its contact (steps 2.10 and 3.3b). conversationId stays null until the client writes (step 2.4b links
     * it then); previousStage is set only in Needs Human, closedAt only in a closing stage; the stage names come from the
     * lead's own version, null for a version the engine cannot run.
     */
    record LeadResponse(
            UUID id,
            String stage,
            String stageName,
            String previousStage,
            String previousStageName,
            Instant stageEnteredAt,
            Instant closedAt,
            int version,
            String origin,
            UUID workflowVersionId,
            UUID assignedMembershipId,
            UUID conversationId,
            Instant createdAt,
            ContactResponse contact) {

        static LeadResponse of(LeadView lead, ContactView contact) {
            return new LeadResponse(
                    lead.id(),
                    lead.stage(),
                    lead.stageName(),
                    lead.previousStage(),
                    lead.previousStageName(),
                    lead.stageEnteredAt(),
                    lead.closedAt(),
                    lead.version(),
                    lead.origin(),
                    lead.workflowVersionId(),
                    lead.assignedMembershipId(),
                    lead.conversationId(),
                    lead.createdAt(),
                    ContactResponse.of(contact));
        }
    }

    /**
     * A page of a lead's timeline (step 3.3b), newest first, with the names of its version's stages for the rows'
     * stage keys.
     */
    record TimelinePageResponse(List<TimelineItemResponse> items, String nextCursor, Map<String, String> stageNames) {}

    /** One timeline row; data is the stored JSON object (IDs, stage keys, codes, numbers and times, never text). */
    record TimelineItemResponse(
            UUID id,
            String type,
            String actorType,
            UUID actorMembershipId,
            String refTable,
            UUID refId,
            String summary,
            JsonNode data,
            Instant occurredAt) {

        static TimelineItemResponse of(Timeline.Entry entry, JsonMapper json) {
            return new TimelineItemResponse(
                    entry.id(),
                    entry.type(),
                    entry.actorType(),
                    entry.actorMembershipId(),
                    entry.refTable(),
                    entry.refId(),
                    entry.summary(),
                    json.readTree(entry.data()),
                    entry.occurredAt());
        }
    }

    record ContactResponse(UUID id, String phone, String name, String email, String consentState, Instant optedOutAt) {

        static ContactResponse of(ContactView contact) {
            return new ContactResponse(
                    contact.id(),
                    contact.phone(),
                    contact.name(),
                    contact.email(),
                    contact.consentState(),
                    contact.optedOutAt());
        }

        @Override
        public String toString() {
            return "ContactResponse[id=" + id + ", consentState=" + consentState + "]";
        }
    }
}
