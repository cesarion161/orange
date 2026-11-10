package com.ai.orange.brief;

import com.ai.orange.brief.BriefSnapshot.Closed;
import com.ai.orange.brief.BriefSnapshot.Item;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.brief.BriefSnapshot.SessionFacts;
import com.ai.orange.brief.BriefSnapshot.TaskFacts;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * Snapshots for the brief's tests, built by hand: {@code snapshot(project("orange-ai").prompts(3))}.
 * Everything not set is empty, zero or "normal", so a test names only what it is about.
 */
public final class BriefFixtures {

    /** When the fixture snapshots were read, and since when they look: a Tuesday morning in Berlin. */
    public static final String AT = "2026-11-10T09:05+01:00";
    public static final String SINCE = "2026-11-09T14:00:03+01:00";
    public static final String ZONE = "Europe/Berlin";
    /** The brief time the fixture briefs answer. */
    public static final String SLOT = "2026-11-10T09:00+01:00";

    private BriefFixtures() {}

    public static ProjectBuilder project(String name) {
        return new ProjectBuilder(name);
    }

    public static SnapshotBuilder snapshot(ProjectBuilder... projects) {
        SnapshotBuilder b = new SnapshotBuilder();
        for (ProjectBuilder p : projects) b.projects.add(p.build());
        return b;
    }

    /** A project with a rhythm and nothing unusual about it. */
    public static Quiet normal() {
        return new Quiet(Quiet.NORMAL, 9, "2026-11-10", 0, 1.0, 5, null);
    }

    public static Quiet quietFor(int days) {
        return new Quiet(Quiet.QUIET, 9, "2026-11-10", days, 1.0, 5, null);
    }

    public static Quiet tooNew() {
        return new Quiet(Quiet.UNKNOWN, 1, "2026-11-10", 0, null, null, null);
    }

    public static Milestone milestone(String text, String dueAt, Integer daysLeft, Integer elapsedPercent,
                                      boolean overdue, Integer closedSince) {
        return new Milestone(text, dueAt, daysLeft, elapsedPercent, overdue, closedSince);
    }

    /** A dated milestone that many days away (negative = that many days overdue), a third of its time gone. */
    public static Milestone milestoneIn(String text, int days) {
        return new Milestone(text, "2026-11-10T23:59+01:00", days, days < 0 ? 100 : 30, days < 0, 0);
    }

    public static SessionFacts session(String state, String branch, String intent) {
        return new SessionFacts(state, branch, intent, "2026-11-10T08:53+01:00",
                BriefSnapshot.WORKING.equals(state) ? null : "2026-11-10T06:05+01:00");
    }

    public static Closed closed(String result, String intent) {
        return new Closed(result, intent, "2026-11-10T07:05+01:00");
    }

    public static Item item(String kind, String urgency, String title) {
        return new Item(kind, urgency, title, "2026-11-10T06:05+01:00");
    }

    /** orange's own tasks: counts by status as pairs ({@code "running", 3}), and what waits for a person. */
    public static TaskFacts tasks(int integratedSince, List<String> needsHuman, Object... byStatus) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < byStatus.length; i += 2) counts.put((String) byStatus[i], (Integer) byStatus[i + 1]);
        return new TaskFacts(counts, integratedSince, needsHuman);
    }

    /** A scheduled brief for {@link #SLOT}, as stored: with the model's lines, or facts only with a note. */
    public static Brief brief(BriefSnapshot snapshot, BriefContent contentOrNull, String note) {
        return brief(OffsetDateTime.parse(SLOT), snapshot.toJsonb(), json(contentOrNull), note);
    }

    /** A brief that was asked for ({@code orange_focus}): it answers no brief time. */
    public static Brief onDemand(BriefSnapshot snapshot, BriefContent contentOrNull, String note) {
        return brief(null, snapshot.toJsonb(), json(contentOrNull), note);
    }

    /** A brief from whatever is stored, with random ids and a page key. */
    public static Brief brief(OffsetDateTime slotOrNull, JSONB snapshot, JSONB contentOrNull, String note) {
        OffsetDateTime at = OffsetDateTime.parse(AT);
        return new Brief(UUID.randomUUID(), slotOrNull, at, snapshot, contentOrNull, note,
                contentOrNull == null ? null : UUID.randomUUID(), slotOrNull != null,
                UUID.randomUUID().toString().replace("-", ""), null, at);
    }

    private static JSONB json(BriefContent content) {
        return content == null ? null : JSONB.valueOf(content.toJson().toString());
    }

    public static final class ProjectBuilder {
        private String id;
        private final String name;
        private String summary;
        private List<String> goals = List.of();
        private final List<Milestone> milestones = new ArrayList<>();
        private final List<SessionFacts> sessions = new ArrayList<>();
        private Integer sessionsTotal;
        private final List<Closed> closed = new ArrayList<>();
        private Integer integrated;
        private Integer verified;
        private final List<Item> waiting = new ArrayList<>();
        private Integer waitingTotal;
        private Integer waitingUrgent;
        private int prompts;
        private String lastPromptAt;
        private Quiet quiet = normal();

        private ProjectBuilder(String name) {
            this.name = name;
            // The same name is the same project in two snapshots.
            this.id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
        }

        public ProjectBuilder id(String id) {
            this.id = id;
            return this;
        }

        public ProjectBuilder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public ProjectBuilder goals(String... goals) {
            this.goals = List.of(goals);
            return this;
        }

        public ProjectBuilder milestone(Milestone milestone) {
            milestones.add(milestone);
            return this;
        }

        public ProjectBuilder session(SessionFacts session) {
            sessions.add(session);
            return this;
        }

        /** How many sessions there are when more than the listed ones. */
        public ProjectBuilder sessionsTotal(int total) {
            this.sessionsTotal = total;
            return this;
        }

        public ProjectBuilder closed(Closed work) {
            closed.add(work);
            return this;
        }

        /** The counts when more work closed than is listed. */
        public ProjectBuilder closedCounts(int integrated, int verified) {
            this.integrated = integrated;
            this.verified = verified;
            return this;
        }

        public ProjectBuilder waiting(Item item) {
            waiting.add(item);
            return this;
        }

        /** The counts when more items are open than are listed. */
        public ProjectBuilder waitingCounts(int total, int urgent) {
            this.waitingTotal = total;
            this.waitingUrgent = urgent;
            return this;
        }

        public ProjectBuilder prompts(int prompts) {
            this.prompts = prompts;
            this.lastPromptAt = prompts > 0 ? "2026-11-10T08:53+01:00" : null;
            return this;
        }

        public ProjectBuilder quiet(Quiet quiet) {
            this.quiet = quiet;
            return this;
        }

        public ProjectFacts build() {
            int urgent = (int) waiting.stream().filter(i -> !"normal".equals(i.urgency())).count();
            return new ProjectFacts(id, name, summary, goals, milestones, sessions,
                    sessionsTotal != null ? sessionsTotal : sessions.size(), closed,
                    integrated != null ? integrated : count("integrated"),
                    verified != null ? verified : count("verified"),
                    waiting, waitingTotal != null ? waitingTotal : waiting.size(),
                    waitingUrgent != null ? waitingUrgent : urgent, prompts, lastPromptAt, quiet);
        }

        private int count(String result) {
            return (int) closed.stream().filter(c -> result.equals(c.result())).count();
        }
    }

    public static final class SnapshotBuilder {
        private String at = AT;
        private String since = SINCE;
        private String zone = ZONE;
        private final List<ProjectFacts> projects = new ArrayList<>();
        private List<String> otherProjects = List.of();
        private int proposedProjects;
        private int pausedProjects;
        private Integer promptsTotal;
        private final List<Item> elsewhere = new ArrayList<>();
        private Integer elsewhereTotal;
        private TaskFacts tasks = new TaskFacts(Map.of(), 0, List.of());

        private SnapshotBuilder() {}

        public SnapshotBuilder at(String at) {
            this.at = at;
            return this;
        }

        public SnapshotBuilder since(String since) {
            this.since = since;
            return this;
        }

        public SnapshotBuilder zone(String zone) {
            this.zone = zone;
            return this;
        }

        public SnapshotBuilder otherProjects(String... names) {
            this.otherProjects = List.of(names);
            return this;
        }

        public SnapshotBuilder proposedAndPaused(int proposed, int paused) {
            this.proposedProjects = proposed;
            this.pausedProjects = paused;
            return this;
        }

        /** The user's prompts in all sessions, when some were outside the projects shown. */
        public SnapshotBuilder promptsTotal(int total) {
            this.promptsTotal = total;
            return this;
        }

        public SnapshotBuilder elsewhere(Item item) {
            elsewhere.add(item);
            return this;
        }

        public SnapshotBuilder elsewhereTotal(int total) {
            this.elsewhereTotal = total;
            return this;
        }

        public SnapshotBuilder tasks(TaskFacts tasks) {
            this.tasks = tasks;
            return this;
        }

        public BriefSnapshot build() {
            int prompts = projects.stream().mapToInt(ProjectFacts::prompts).sum();
            return new BriefSnapshot(at, since, zone, projects, otherProjects, proposedProjects, pausedProjects,
                    promptsTotal != null ? promptsTotal : prompts, elsewhere,
                    elsewhereTotal != null ? elsewhereTotal : elsewhere.size(), tasks);
        }
    }
}
