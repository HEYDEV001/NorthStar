package com.northstar.integrations.internal;

import com.northstar.contacts.TemplateStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The template log lines the webhook job and the sync share (step 2.8b): Northstar's template ID, statuses, Meta's
 * categories, where the change came from and Meta's rejection reason as a fixed code; never a name, text or Meta's ID.
 * A template newly unable to send is a WARN, the admin's alert until Phase 8.
 */
final class TemplateLogs {

    private static final Logger log = LoggerFactory.getLogger("com.northstar.integrations.whatsapp.templates");

    private TemplateLogs() {}

    static void statusChanged(UUID templateId, TemplateStatus from, TemplateStatus to, String source, String reason) {
        String fromCode = from == null ? "none" : from.code();
        if (TemplateStatuses.blocking(to)) {
            log.warn(
                    "whatsapp.template.status_changed template_id={} from={} to={} source={} reason={}",
                    templateId,
                    fromCode,
                    to.code(),
                    source,
                    reason);
        } else {
            log.info(
                    "whatsapp.template.status_changed template_id={} from={} to={} source={} reason={}",
                    templateId,
                    fromCode,
                    to.code(),
                    source,
                    reason);
        }
    }

    /** What a template's status did to its sends (step 2.8c): counts only, logged when there are any. */
    static void sends(UUID templateId, TemplateStatus status, TemplateDependents.Result sends, String source) {
        log.info(
                "whatsapp.template.sends template_id={} status={} blocked={} resumed={} failed={} source={}",
                templateId,
                status.code(),
                sends.blocked(),
                sends.resumed(),
                sends.failed(),
                source);
    }

    static void categoryChanged(UUID templateId, String from, String to, String source) {
        log.info(
                "whatsapp.template.category_changed template_id={} from={} to={} source={}",
                templateId,
                from == null ? "none" : from,
                to,
                source);
    }
}
