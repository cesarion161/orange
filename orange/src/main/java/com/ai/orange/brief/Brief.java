package com.ai.orange.brief;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * One focus brief (project-space spec §7; Phase D): the facts orange read ({@code snapshot}) and,
 * when a brief run added them, the model's lines ({@code content}).
 *
 * @param slotAt      the brief time it answers; null when it was asked for ({@code orange_focus})
 * @param snapshotAt  when the facts were read; the next scheduled brief covers what happened since
 * @param content     what {@code submit_brief} said; null = facts only
 * @param note        why there are no model lines
 * @param pushable    a scheduled brief with something new: it goes to the phone
 * @param pageKey     opens this brief's page and nothing else
 * @param deliveredAt when ntfy took the push
 */
public record Brief(UUID id, OffsetDateTime slotAt, OffsetDateTime snapshotAt, JSONB snapshot, JSONB content,
                    String note, UUID runId, boolean pushable, String pageKey, OffsetDateTime deliveredAt,
                    OffsetDateTime createdAt) {

    public boolean scheduled() {
        return slotAt != null;
    }
}
