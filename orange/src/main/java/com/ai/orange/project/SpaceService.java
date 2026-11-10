package com.ai.orange.project;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's space: goals, milestones, conventions, decisions, notes and questions. Content is
 * never edited in place; a change inserts a new item that supersedes the old one, so the space
 * keeps its history. Agents only propose (spec decision 1): their items start {@code proposed}.
 */
@Service
public class SpaceService {

    private final SpaceRepository items;

    public SpaceService(SpaceRepository items) {
        this.items = items;
    }

    /** Who wrote an item: {@code user}, {@code orange}, or {@code agent} with the run or session it came from. */
    public record Author(String kind, String ref) {
        public static final Author USER = new Author(SpaceItem.USER, null);
        public static final Author ORANGE = new Author(SpaceItem.ORANGE, null);

        public Author {
            if (!SpaceItem.AUTHORS.contains(kind)) throw new IllegalArgumentException("unknown author kind: " + kind);
        }

        public static Author agent(String ref) {
            return new Author(SpaceItem.AGENT, ref);
        }

        String initialStatus() {
            return SpaceItem.AGENT.equals(kind) ? SpaceItem.PROPOSED : SpaceItem.ACTIVE;
        }
    }

    /**
     * Adds a goal, milestone, decision, note or question. With {@code supersedes}, the new item
     * replaces that one (which must be in the same project and still current).
     */
    @Transactional
    public SpaceItem add(UUID projectId, String kind, String text, Author author, OffsetDateTime dueAt,
                         UUID sessionId, UUID supersedes) {
        if (!SpaceItem.KINDS.contains(kind) || SpaceItem.CONVENTION.equals(kind)) {
            throw new IllegalArgumentException("kind must be goal, milestone, decision, note or question");
        }
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (dueAt != null && !SpaceItem.MILESTONE.equals(kind)) {
            throw new IllegalArgumentException("only a milestone has a due date");
        }
        String status = author.initialStatus();
        if (supersedes != null) supersede(projectId, supersedes, status);
        ObjectNode body = Json.mapper().createObjectNode().put("text", text.strip());
        return items.insert(projectId, sessionId, kind, null, JSONB.valueOf(body.toString()), status, author.kind(),
                author.ref(), dueAt, supersedes);
    }

    /**
     * Sets a convention for the project, or for one session when {@code sessionId} is given. The
     * value it had is superseded. An agent's value is only proposed and changes nothing yet.
     */
    @Transactional
    public SpaceItem setConvention(UUID projectId, UUID sessionId, String key, JsonNode value, Author author) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a convention needs a key");
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new IllegalArgumentException("a convention needs a value");
        }
        String status = author.initialStatus();
        UUID supersedes = null;
        if (SpaceItem.ACTIVE.equals(status)) {
            Optional<SpaceItem> current = items.activeConvention(projectId, sessionId, key.strip());
            if (current.isPresent()) {
                supersedes = current.get().id();
                items.setStatus(supersedes, Set.of(SpaceItem.ACTIVE), SpaceItem.SUPERSEDED);
            }
        }
        ObjectNode body = Json.mapper().createObjectNode();
        body.set("value", value);
        return items.insert(projectId, sessionId, SpaceItem.CONVENTION, key.strip(), JSONB.valueOf(body.toString()),
                status, author.kind(), author.ref(), null, supersedes);
    }

    /** Makes a proposal active. A proposed convention replaces the active value of its key. */
    @Transactional
    public SpaceItem accept(UUID id) {
        SpaceItem item = items.findById(id).orElseThrow(() -> new IllegalArgumentException("no such item: " + id));
        if (!SpaceItem.PROPOSED.equals(item.status())) {
            throw new IllegalArgumentException("only a proposal can be accepted; this item is " + item.status());
        }
        if (SpaceItem.CONVENTION.equals(item.kind())) {
            items.activeConvention(item.projectId(), item.sessionId(), item.key())
                    .ifPresent(old -> items.setStatus(old.id(), Set.of(SpaceItem.ACTIVE), SpaceItem.SUPERSEDED));
        } else if (item.supersedesId() != null) {
            items.setStatus(item.supersedesId(), Set.of(SpaceItem.ACTIVE), SpaceItem.SUPERSEDED);
        }
        items.setStatus(id, Set.of(SpaceItem.PROPOSED), SpaceItem.ACTIVE);
        return items.findById(id).orElseThrow();
    }

    /** Drops a proposal or an active item. */
    @Transactional
    public SpaceItem drop(UUID id) {
        return move(id, SpaceItem.DROPPED);
    }

    /** Marks an active goal, milestone or question as done. */
    @Transactional
    public SpaceItem done(UUID id) {
        return move(id, SpaceItem.DONE);
    }

    private SpaceItem move(UUID id, String to) {
        SpaceItem item = items.findById(id).orElseThrow(() -> new IllegalArgumentException("no such item: " + id));
        Set<String> from = SpaceItem.DONE.equals(to) ? Set.of(SpaceItem.ACTIVE)
                : Set.of(SpaceItem.ACTIVE, SpaceItem.PROPOSED);
        if (!items.setStatus(id, from, to)) {
            throw new IllegalArgumentException("this item is " + item.status() + " and cannot become " + to);
        }
        return items.findById(id).orElseThrow();
    }

    /** What is current in a project's space: active items and open proposals, oldest first. */
    public List<SpaceItem> current(UUID projectId) {
        return items.find(projectId, Set.of(SpaceItem.ACTIVE, SpaceItem.PROPOSED));
    }

    public Optional<SpaceItem> find(UUID id) {
        return items.findById(id);
    }

    private void supersede(UUID projectId, UUID oldId, String newStatus) {
        SpaceItem old = items.findById(oldId)
                .orElseThrow(() -> new IllegalArgumentException("no such item to supersede: " + oldId));
        if (!old.projectId().equals(projectId)) {
            throw new IllegalArgumentException("the superseded item belongs to another project");
        }
        if (!SpaceItem.ACTIVE.equals(old.status())) {
            throw new IllegalArgumentException("only an active item can be superseded; that one is " + old.status());
        }
        // A proposal supersedes only once it is accepted.
        if (SpaceItem.ACTIVE.equals(newStatus)) items.setStatus(oldId, Set.of(SpaceItem.ACTIVE), SpaceItem.SUPERSEDED);
    }
}
