package com.ai.orange.brief;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jooq.JSONB;

/**
 * The words a brief run adds to the facts ({@code submit_brief}): one line per project, what to
 * focus on next and, rarely, something urgent. It holds no number and no date; those are
 * orange's own ({@link BriefSnapshot}). {@link #parse} is the only way a model's call becomes
 * one, and it refuses whatever a brief may not carry.
 *
 * @param projects one status line per project of the run
 * @param focus    what the user should do next, most important first
 * @param urgent   what cannot wait for the next brief
 */
public record BriefContent(List<Line> projects, List<Focus> focus, List<Urgent> urgent) {

    public static final int MAX_STATUS = 200;
    public static final int MAX_ITEM = 160;
    public static final int MAX_WHY = 300;
    public static final int MAX_TITLE = 120;
    public static final int MAX_FOCUS = 3;
    public static final int MAX_URGENT = 3;

    /** Whatever ends a line, the Unicode separators included: a brief's texts are one line each. */
    private static final Pattern LINE_BREAK = Pattern.compile("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]");

    public BriefContent {
        projects = projects == null ? List.of() : List.copyOf(projects);
        focus = focus == null ? List.of() : List.copyOf(focus);
        urgent = urgent == null ? List.of() : List.copyOf(urgent);
    }

    /** What moved in a project and how it stands, in one line. */
    public record Line(String project, String statusLine) {}

    /** Something the user can do next, and why. */
    public record Focus(String project, String item, String why) {}

    /** Something that cannot wait for the next brief. */
    public record Urgent(String project, String title, String why) {}

    /** The model's line for {@code project}, when it wrote one that says something. */
    public Optional<String> statusLine(String project) {
        for (Line line : projects) {
            if (line.project() != null && line.project().equals(project) && line.statusLine() != null
                    && !line.statusLine().isBlank()) {
                return Optional.of(line.statusLine());
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a {@code submit_brief} call for a run that writes about {@code projects}.
     *
     * @throws IllegalArgumentException with a message that tells the model what to correct
     */
    public static BriefContent parse(JsonNode args, List<String> projects) {
        List<String> known = projects == null ? List.of() : projects;
        if (args == null || !args.isObject()) throw new IllegalArgumentException("the arguments must be an object");

        List<Line> lines = new ArrayList<>();
        Set<String> written = new LinkedHashSet<>();
        for (JsonNode entry : array(args, "projects", true)) {
            object(entry, "projects");
            String project = project(entry, "projects", known);
            if (!written.add(project)) {
                throw new IllegalArgumentException("project '" + project + "' has two entries in projects; give it one");
            }
            lines.add(new Line(project, text(entry, "status_line", "projects", MAX_STATUS)));
        }
        List<String> missing = known.stream().filter(p -> !written.contains(p)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("projects has no entry for: " + String.join(", ", missing)
                    + "; write one status_line for every project in your prompt");
        }

        List<Focus> focus = new ArrayList<>();
        for (JsonNode entry : array(args, "focus", true)) {
            object(entry, "focus");
            focus.add(new Focus(project(entry, "focus", known), text(entry, "item", "focus", MAX_ITEM),
                    text(entry, "why", "focus", MAX_WHY)));
        }
        if (focus.size() > MAX_FOCUS) {
            throw new IllegalArgumentException("focus has " + focus.size() + " entries; give at most " + MAX_FOCUS
                    + ", the most important first");
        }

        List<Urgent> urgent = new ArrayList<>();
        Set<String> urgentProjects = new LinkedHashSet<>();
        for (JsonNode entry : array(args, "urgent", false)) {
            object(entry, "urgent");
            String project = project(entry, "urgent", known);
            if (!urgentProjects.add(project)) {
                throw new IllegalArgumentException("urgent has two entries for project '" + project
                        + "'; give at most one per project");
            }
            urgent.add(new Urgent(project, text(entry, "title", "urgent", MAX_TITLE),
                    text(entry, "why", "urgent", MAX_WHY)));
        }
        if (urgent.size() > MAX_URGENT) {
            throw new IllegalArgumentException("urgent has " + urgent.size() + " entries; give at most " + MAX_URGENT);
        }
        return new BriefContent(lines, focus, urgent);
    }

    /** As stored in {@code briefs.content} and in the run's outcome; the keys are {@code submit_brief}'s. */
    public ObjectNode toJson() {
        ObjectNode node = Json.mapper().createObjectNode();
        ArrayNode p = node.putArray("projects");
        for (Line l : projects) p.addObject().put("project", l.project()).put("status_line", l.statusLine());
        ArrayNode f = node.putArray("focus");
        for (Focus x : focus) f.addObject().put("project", x.project()).put("item", x.item()).put("why", x.why());
        ArrayNode u = node.putArray("urgent");
        for (Urgent x : urgent) u.addObject().put("project", x.project()).put("title", x.title()).put("why", x.why());
        return node;
    }

    /**
     * A stored brief's content, as it is: it was validated when it was written. Empty for null and
     * for anything that is not such an object; an entry that is not an object is skipped and a
     * missing text is empty, so whatever is stored renders.
     */
    public static Optional<BriefContent> read(JSONB stored) {
        if (stored == null || stored.data() == null) return Optional.empty();
        try {
            return read(Json.mapper().readTree(stored.data()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** As {@link #read(JSONB)}, for content that is already a tree (a run's outcome). */
    public static Optional<BriefContent> read(JsonNode node) {
        if (node == null || !node.isObject()) return Optional.empty();
        List<Line> lines = new ArrayList<>();
        for (JsonNode e : node.path("projects")) {
            if (e.isObject()) lines.add(new Line(stored(e, "project"), stored(e, "status_line")));
        }
        List<Focus> focus = new ArrayList<>();
        for (JsonNode e : node.path("focus")) {
            if (e.isObject()) focus.add(new Focus(stored(e, "project"), stored(e, "item"), stored(e, "why")));
        }
        List<Urgent> urgent = new ArrayList<>();
        for (JsonNode e : node.path("urgent")) {
            if (e.isObject()) urgent.add(new Urgent(stored(e, "project"), stored(e, "title"), stored(e, "why")));
        }
        return Optional.of(new BriefContent(lines, focus, urgent));
    }

    /** The projects a brief run may write about: the {@code projects} names of its input. */
    public static List<String> projectsOf(JSONB runInput) {
        if (runInput == null || runInput.data() == null) return List.of();
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode n : Json.mapper().readTree(runInput.data()).path("projects")) {
                if (n.isTextual()) names.add(n.asText());
            }
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static JsonNode array(JsonNode args, String key, boolean required) {
        JsonNode value = args.get(key);
        if (value == null || value.isNull()) {
            if (required) throw new IllegalArgumentException(key + " is missing; it must be an array");
            return Json.mapper().createArrayNode();
        }
        if (!value.isArray()) throw new IllegalArgumentException(key + " must be an array");
        return value;
    }

    private static void object(JsonNode entry, String list) {
        if (entry == null || !entry.isObject()) {
            throw new IllegalArgumentException("each entry of " + list + " must be an object");
        }
    }

    /** The entry's project as the run names it: the exact name, else the one that differs only in case. */
    private static String project(JsonNode entry, String list, List<String> known) {
        JsonNode value = entry.get("project");
        String name = value != null && value.isTextual() ? value.asText().strip() : "";
        if (name.isEmpty()) throw new IllegalArgumentException("an entry of " + list + " has no project");
        if (known.contains(name)) return name;
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String k : known) byLower.putIfAbsent(k.toLowerCase(Locale.ROOT), k);
        String canonical = byLower.get(name.toLowerCase(Locale.ROOT));
        if (canonical != null) return canonical;
        throw new IllegalArgumentException(list + " names a project that is not in your prompt: '" + cut(name)
                + "'. The projects are: " + String.join(", ", known));
    }

    private static String text(JsonNode entry, String field, String list, int max) {
        String where = field + " in " + list;
        JsonNode value = entry.get(field);
        if (value == null || value.isNull()) throw new IllegalArgumentException(where + " is missing");
        if (!value.isTextual()) throw new IllegalArgumentException(where + " must be text");
        String text = value.asText().strip();
        if (text.isEmpty()) throw new IllegalArgumentException(where + " must not be blank");
        if (LINE_BREAK.matcher(text).find()) {
            throw new IllegalArgumentException(where + " must be one line, with no line break");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://")) {
            throw new IllegalArgumentException(where + " must not contain a link; name things in words");
        }
        if (text.length() > max) {
            throw new IllegalArgumentException(where + " has " + text.length() + " characters; the limit is " + max);
        }
        return text;
    }

    private static String stored(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /** A refused name goes back to the model, not into a log line of any length. */
    private static String cut(String name) {
        return name.length() <= MAX_TITLE ? name : name.substring(0, MAX_TITLE) + "…";
    }
}
