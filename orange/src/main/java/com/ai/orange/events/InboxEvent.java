package com.ai.orange.events;

import org.jooq.JSONB;

/** One stored inbound event, e.g. a GitHub webhook delivery. */
public record InboxEvent(long id, String source, String type, String repo, String deliveryId, JSONB payload,
                         int attempts) {}
