package com.ai.orange.project;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The global defaults of the conventions ({@code orange.conventions}; project-space spec §8).
 * A project's space and a session's overrides take precedence, see {@link Conventions}.
 * {@code evidence}, {@code base-branch}, {@code remote-push} and {@code cadence} have no global
 * value: they come from the repo or stay unset. A {@code brief-times} or {@code quiet-hours}
 * entry that is not a time stops the start: read leniently, a typo would silently mean no brief
 * or no quiet hours.
 */
@ConfigurationProperties(prefix = "orange.conventions")
public record ConventionsProperties(@DefaultValue({"evidence", "integrated"}) List<String> done,
                                    @DefaultValue("merge") String integration,
                                    @DefaultValue("routine") String autoAnswer,
                                    @DefaultValue List<String> alwaysAsk,
                                    @DefaultValue("0.8") double autoAnswerConfidence,
                                    @DefaultValue({"09:00", "14:00"}) List<String> briefTimes,
                                    @DefaultValue({"22:00", "08:00"}) List<String> quietHours) {

    public ConventionsProperties {
        done = done == null ? List.of() : List.copyOf(done);
        alwaysAsk = alwaysAsk == null ? List.of() : List.copyOf(alwaysAsk);
        briefTimes = briefTimes == null ? List.of() : List.copyOf(briefTimes);
        quietHours = quietHours == null ? List.of() : List.copyOf(quietHours);
        times("brief-times", briefTimes);
        times("quiet-hours", quietHours);
        if (!quietHours.isEmpty() && quietHours.size() != 2) {
            throw new IllegalArgumentException("orange.conventions.quiet-hours: give two times (from, to) or "
                    + "none, not " + quietHours.size());
        }
    }

    private static void times(String key, List<String> values) {
        for (String value : values) {
            try {
                LocalTime.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("orange.conventions." + key + ": '" + value
                        + "' is not a time like 09:00");
            }
        }
    }

    /** The defaults as the JSON a convention item would hold, by key. */
    public ObjectNode asJson() {
        ObjectNode n = Json.mapper().createObjectNode();
        n.set("done", Json.mapper().valueToTree(done));
        n.put("integration", integration);
        n.put("auto-answer", autoAnswer);
        n.set("always-ask", Json.mapper().valueToTree(alwaysAsk));
        n.put("auto-answer-confidence", autoAnswerConfidence);
        n.set("brief-times", Json.mapper().valueToTree(briefTimes));
        n.set("quiet-hours", Json.mapper().valueToTree(quietHours));
        return n;
    }

    public JsonNode get(String key) {
        return asJson().get(key);
    }
}
