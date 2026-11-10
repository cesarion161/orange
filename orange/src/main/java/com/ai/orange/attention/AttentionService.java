package com.ai.orange.attention;

import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attention inbox: one place for everything that wants the user. Filing is idempotent per
 * dedupe key, so a rule that fires twice for the same condition files one item.
 */
@Service
public class AttentionService {

    private static final Logger log = LoggerFactory.getLogger(AttentionService.class);

    private final AttentionRepository items;
    private final ProjectRepository projects;

    public AttentionService(AttentionRepository items, ProjectRepository projects) {
        this.items = items;
        this.projects = projects;
    }

    /** Files an item, or returns the one already filed under {@code dedupeKey}. */
    public AttentionItem raise(String kind, String dedupeKey, String urgency, UUID projectId, UUID sessionId,
                               String title, String body) {
        Optional<AttentionItem> created = items.insert(kind, dedupeKey, urgency, projectId, sessionId, null, title,
                body, null);
        if (created.isPresent()) {
            log.info("attention: {} {} item {} — {}", urgency, kind, created.get().id(), title);
            return created.get();
        }
        return items.findByKey(dedupeKey).orElseThrow();
    }

    public List<AttentionItem> list(String status, int limit) {
        return items.find(status, Math.clamp(limit, 1, 200));
    }

    public Optional<AttentionItem> find(UUID id) {
        return items.findById(id);
    }

    public boolean ack(UUID id) {
        return items.ack(id);
    }

    /**
     * The user's answer to an item. For a proposed project the answer decides its fate:
     * {@code confirm} activates it, {@code ignore} archives it.
     *
     * @return the resolved item; empty when there is no such item
     * @throws IllegalArgumentException when a proposed project gets an answer other than confirm or ignore
     */
    @Transactional
    public Optional<AttentionItem> resolve(UUID id, String answer, String by) {
        Optional<AttentionItem> found = items.findById(id);
        if (found.isEmpty()) return Optional.empty();
        AttentionItem item = found.get();
        if (AttentionItem.RESOLVED.equals(item.status())) return found;
        if (AttentionItem.PROJECT_PROPOSED.equals(item.kind()) && item.projectId() != null) {
            String a = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
            String status = switch (a) {
                case "confirm", "yes", "active" -> Project.ACTIVE;
                case "ignore", "no", "archive" -> Project.ARCHIVED;
                default -> throw new IllegalArgumentException("answer a proposed project with confirm or ignore");
            };
            projects.findById(item.projectId()).filter(p -> Project.PROPOSED.equals(p.status()))
                    .ifPresent(p -> projects.update(p.id(), p.name(), status, p.summary()));
        }
        items.resolve(id, by, answer);
        return items.findById(id);
    }
}
