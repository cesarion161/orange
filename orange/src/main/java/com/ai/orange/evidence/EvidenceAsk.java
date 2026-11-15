package com.ai.orange.evidence;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.WorkConventions;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A project without evidence commands cannot have its work verified, a session's or a task's.
 * orange says so once per project and proposes what the repo suggests; the proposal is the user's
 * to accept, because these commands run their code (project-space spec decision 13).
 */
@Component
public class EvidenceAsk {

    public static final String EVIDENCE_MISSING = "evidence_missing";

    private final SpaceService space;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;

    public EvidenceAsk(SpaceService space, AttentionService attention, AttentionRepository attentionItems) {
        this.space = space;
        this.attention = attention;
        this.attentionItems = attentionItems;
    }

    public static String key(Project project) {
        return "project:" + project.id() + ":" + EVIDENCE_MISSING;
    }

    /** Files the item, once per project (a user who dropped the proposal does not get it again). */
    public void ask(Project project, List<String> detected) {
        if (project == null) return;
        if (attentionItems.findByKey(key(project)).isPresent()) return;
        StringBuilder body = new StringBuilder("orange cannot tell when work in ").append(project.name())
                .append(" is done: the project has no evidence commands, so its work is not verified or integrated.\n");
        if (detected.isEmpty()) {
            body.append("Set them with orange_set: key \"evidence\", value a list of commands such as "
                    + "[\"./gradlew test\"].");
        } else {
            ArrayNode value = Json.mapper().createArrayNode();
            detected.forEach(value::add);
            SpaceItem proposal = space.proposeConvention(project.id(), WorkConventions.EVIDENCE, value);
            body.append("From the repo, orange would run: ").append(String.join(", ", detected))
                    .append(".\nAccept that with orange_accept ").append(proposal.id())
                    .append(", or set your own with orange_set (key \"evidence\").");
        }
        body.append("\nIf this project needs no checks, set its \"done\" convention to [\"committed\"] or [].");
        attention.raise(EVIDENCE_MISSING, key(project), AttentionItem.NORMAL, project.id(), null,
                project.name() + " has no evidence commands", body.toString());
    }
}
