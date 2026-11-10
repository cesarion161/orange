package com.ai.orange.brief;

import com.ai.orange.brief.BriefSnapshot.Closed;
import com.ai.orange.brief.BriefSnapshot.Item;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.brief.BriefSnapshot.SessionFacts;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.util.HtmlUtils;

/**
 * A brief as the user reads it: the push, the {@code orange_focus} text and the page. The
 * model's lines are shown where it wrote them; every count, date and "quiet" is read from the
 * snapshot again and printed beside them, whatever the lines say, and a brief with no model
 * lines is written from the counts by rule. The page then shows the facts the lines were
 * written from, so the words can be checked against them.
 */
public final class BriefText {

    /** A push is a glance: this much of the text, and the page has the rest. */
    public static final int MAX_PUSH = 1_500;
    /** How many dated milestones stand beside a project's line, and how much of each one's text. */
    static final int MILESTONES_PER_LINE = 2;
    static final int MILESTONE_CHARS = 60;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private static final String STYLE = """
            body{font:16px/1.45 system-ui,sans-serif;max-width:44rem;margin:1.5rem auto;padding:0 1rem;color:#222;background:#fff}
            h1{font-size:1.3rem;margin-bottom:.2rem}h2{font-size:1.1rem;margin-top:1.6rem}
            h3{font-size:1rem;margin-top:1.4rem;border-top:1px solid #ccc;padding-top:.8rem}
            h4{font-size:.9rem;margin:.8rem 0 .2rem}ul,ol{margin:.2rem 0;padding-left:1.3rem}li{margin:.15rem 0}
            .when,.facts,.hint{color:#666}.note{border-left:3px solid #c80;padding-left:.6rem}.urgent li{font-weight:600}
            @media(prefers-color-scheme:dark){body{color:#ddd;background:#111}.when,.facts,.hint{color:#999}h3{border-color:#444}}
            """;

    private BriefText() {}

    /** {@code orange brief · 09:00 · 3 projects}, plus {@code · facts only} when no model wrote a line. */
    public static String title(Brief b) {
        return title(b, view(b));
    }

    /** The whole brief as plain text: what a push and {@code orange_focus} carry. */
    public static String text(Brief b) {
        View v = view(b);
        StringBuilder t = new StringBuilder(v.when()).append('\n');
        if (v.note() != null) t.append(v.note()).append('\n');
        if (!v.urgent().isEmpty()) {
            t.append("\nUrgent\n");
            for (Entry u : v.urgent()) t.append("! ").append(u.words()).append('\n');
        }
        if (!v.focus().isEmpty()) {
            t.append("\nFocus\n");
            int n = 1;
            for (Entry f : v.focus()) t.append(n++).append(". ").append(f.words()).append('\n');
        }
        if (!v.projects().isEmpty()) {
            t.append("\nProjects\n");
            for (ProjectLine p : v.projects()) {
                t.append(p.name()).append(" — ").append(p.line());
                if (!p.facts().isEmpty()) t.append(" [").append(String.join("; ", p.facts())).append(']');
                t.append('\n');
            }
        }
        t.append('\n').append(v.waiting()).append('\n');
        for (String more : v.more()) t.append(more).append('\n');
        return t.toString().stripTrailing();
    }

    /**
     * A project's line when no model wrote one, from the counts only:
     * {@code 2 sessions (1 waiting on you); 1 closed since the last brief}.
     */
    static String ruleLine(ProjectFacts p) {
        List<String> parts = new ArrayList<>();
        int sessions = Math.max(p.sessionsTotal(), p.sessions().size());
        long waiting = p.sessions().stream().filter(x -> BriefSnapshot.ASKING.equals(x.state())
                || BriefSnapshot.NEEDS_PERMISSION.equals(x.state())).count();
        if (sessions > 0) {
            parts.add(BriefPrompt.count(sessions, "session", "sessions")
                    + (waiting > 0 ? " (" + waiting + " waiting on you)" : ""));
        }
        int closed = BriefPrompt.closedCount(p);
        if (closed > 0) parts.add(closed + " closed since the last brief");
        if (p.prompts() > 0) parts.add(BriefPrompt.count(p.prompts(), "prompt", "prompts") + " from you");
        return parts.isEmpty() ? "nothing moved" : String.join("; ", parts);
    }

    /** {@link #text} cut to what a push carries. */
    public static String push(Brief b) {
        String text = text(b);
        if (text.length() <= MAX_PUSH) return text;
        int end = MAX_PUSH - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).stripTrailing() + "…";
    }

    /**
     * The brief's page: the brief as in {@link #text}, then the facts it was written from. A whole
     * document with no script and nothing loaded from elsewhere; every value orange stored (and
     * so everything a session, an agent or the model wrote) is escaped.
     */
    public static String html(Brief b) {
        View v = view(b);
        BriefSnapshot s = v.snapshot();
        StringBuilder h = new StringBuilder();
        h.append("<h1>").append(esc(title(b, v))).append("</h1>\n");
        h.append("<p class=\"when\">").append(esc(v.when())).append("</p>\n");
        if (v.note() != null) h.append("<p class=\"note\">").append(esc(v.note())).append("</p>\n");
        if (!v.urgent().isEmpty()) {
            h.append("<h2>Urgent</h2>\n<ul class=\"urgent\">\n");
            for (Entry u : v.urgent()) entry(h, u);
            h.append("</ul>\n");
        }
        if (!v.focus().isEmpty()) {
            h.append("<h2>Focus</h2>\n<ol>\n");
            for (Entry f : v.focus()) entry(h, f);
            h.append("</ol>\n");
        }
        if (!v.projects().isEmpty()) {
            h.append("<h2>Projects</h2>\n<ul>\n");
            for (ProjectLine p : v.projects()) {
                h.append("<li><b>").append(esc(p.name())).append("</b> — ").append(esc(p.line()));
                if (!p.facts().isEmpty()) {
                    h.append(" <span class=\"facts\">[").append(esc(String.join("; ", p.facts()))).append("]</span>");
                }
                h.append("</li>\n");
            }
            h.append("</ul>\n");
        }
        h.append("<p>").append(esc(v.waiting())).append("</p>\n");
        for (String more : v.more()) h.append("<p>").append(esc(more)).append("</p>\n");

        h.append("<h2>Facts</h2>\n<p class=\"hint\">What orange read from its database. The lines above were "
                + "written from this; the numbers and dates are orange's own.</p>\n");
        for (ProjectFacts p : s.projects()) facts(h, p, v);
        elsewhere(h, v);
        return page(h.toString());
    }

    /** The page when there is no brief to show. */
    public static String empty() {
        return page("<h1>orange brief</h1>\n<p>No brief yet.</p>\n");
    }

    // ─────────────────────────── the brief, read once ───────────────────────────

    /** What {@code text} and {@code html} both print, so they cannot disagree. */
    private record View(BriefSnapshot snapshot, boolean modelLines, ZoneId zone, Instant at, String when, String clock,
                        String note, List<Entry> urgent, List<Entry> focus, List<ProjectLine> projects, String waiting,
                        List<String> more) {}

    /** One urgent or focus entry: {@code project: what — why}. */
    private record Entry(String project, String what, String why) {
        String words() {
            return project + ": " + what + (why.isEmpty() ? "" : " — " + why);
        }
    }

    /** @param facts orange's own, printed beside the line whoever wrote it */
    private record ProjectLine(String name, String line, List<String> facts) {}

    private static View view(Brief b) {
        BriefSnapshot s = snapshot(b);
        Optional<BriefContent> content = BriefContent.read(b.content());
        ZoneId zone = BriefPrompt.zone(s);
        Instant read = BriefPrompt.instant(s.at());
        Instant at = read != null ? read : instant(b.snapshotAt());

        StringBuilder when = new StringBuilder(at == null ? "orange brief" : WHEN.format(at.atZone(zone)));
        Instant since = BriefPrompt.instant(s.since());
        if (since != null) when.append(" · since ").append(WHEN.format(since.atZone(zone)));
        if (!b.scheduled()) when.append(" (on demand)");
        Instant slot = b.scheduled() ? instant(b.slotAt()) : at;
        String clock = slot == null ? "--:--" : CLOCK.format(slot.atZone(zone));

        List<Entry> urgent = new ArrayList<>();
        List<Entry> focus = new ArrayList<>();
        content.ifPresent(c -> {
            c.urgent().forEach(u -> urgent.add(new Entry(BriefPrompt.line(u.project(), BriefSnapshot.TITLE),
                    BriefPrompt.line(u.title(), BriefContent.MAX_TITLE), BriefPrompt.line(u.why(), BriefContent.MAX_WHY))));
            c.focus().forEach(f -> focus.add(new Entry(BriefPrompt.line(f.project(), BriefSnapshot.TITLE),
                    BriefPrompt.line(f.item(), BriefContent.MAX_ITEM), BriefPrompt.line(f.why(), BriefContent.MAX_WHY))));
        });

        List<ProjectLine> projects = new ArrayList<>();
        int waiting = 0;
        int urgentWaiting = 0;
        for (ProjectFacts p : s.projects()) {
            String line = content.flatMap(c -> c.statusLine(p.name()))
                    .map(l -> BriefPrompt.line(l, BriefContent.MAX_STATUS)).filter(l -> !l.isEmpty())
                    .orElseGet(() -> ruleLine(p));
            projects.add(new ProjectLine(BriefPrompt.line(p.name(), BriefSnapshot.TITLE), line, facts(p)));
            waiting += Math.max(p.waitingTotal(), p.waiting().size());
            urgentWaiting += p.waitingUrgent();
        }
        // Counted from the snapshot, never from the model's lines: what waits on the user is not the model's to say.
        int outside = Math.max(s.elsewhereTotal(), s.elsewhere().size());
        urgentWaiting += (int) s.elsewhere().stream().filter(i -> "urgent".equals(i.urgency())
                || "critical".equals(i.urgency())).count();
        String waitingLine = "Waiting on you: " + BriefPrompt.waitingWords(waiting + outside, urgentWaiting)
                + (outside > 0 && !s.projects().isEmpty() ? ", " + outside + " outside these projects" : "");

        List<String> more = new ArrayList<>();
        String tasks = BriefPrompt.tasksWords(s.tasks());
        if (tasks != null) more.add("orange's tasks: " + tasks);
        if (!s.otherProjects().isEmpty()) more.add("Active projects not shown: " + BriefPrompt.names(s.otherProjects()));
        if (s.proposedProjects() > 0) {
            more.add(s.proposedProjects() + (s.proposedProjects() == 1 ? " project awaits" : " projects await")
                    + " your confirmation");
        }
        String note = b.note() == null || b.note().isBlank() ? null : BriefPrompt.line(b.note(), 300);
        return new View(s, content.isPresent(), zone, at, when.toString(), clock, note, urgent, focus, projects,
                waitingLine, more);
    }

    private static String title(Brief b, View v) {
        int n = v.snapshot().projects().size();
        String title = "orange brief · " + v.clock() + " · " + BriefPrompt.count(n, "project", "projects");
        return v.modelLines() ? title : title + " · facts only";
    }

    /** The dated milestones nearest first, and since when the project is quiet. */
    private static List<String> facts(ProjectFacts p) {
        List<String> facts = new ArrayList<>();
        p.milestones().stream().filter(m -> m.daysLeft() != null)
                .sorted(Comparator.comparingInt(Milestone::daysLeft))
                .limit(MILESTONES_PER_LINE)
                .forEach(m -> facts.add((BriefPrompt.line(m.text(), MILESTONE_CHARS) + " " + due(m.daysLeft())).strip()));
        if (p.quiet().quiet()) {
            String last = BriefPrompt.line(p.quiet().lastActiveDay(), 20);
            facts.add(last.isEmpty() ? "quiet" : "quiet since " + last);
        }
        return facts;
    }

    /** {@code due in 3 days}, {@code due today} or {@code 2 days overdue}. */
    private static String due(int daysLeft) {
        return daysLeft < 0 ? BriefPrompt.dueWords(daysLeft) : "due " + BriefPrompt.dueWords(daysLeft);
    }

    /** A stored brief always renders: a snapshot that cannot be read is an empty one. */
    private static BriefSnapshot snapshot(Brief b) {
        try {
            return BriefSnapshot.parse(b.snapshot());
        } catch (IllegalArgumentException e) {
            return new BriefSnapshot(null, null, null, null, null, 0, 0, 0, null, 0, null);
        }
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }

    // ─────────────────────────── the page ───────────────────────────

    private static void entry(StringBuilder h, Entry e) {
        h.append("<li><b>").append(esc(e.project())).append("</b>: ").append(esc(e.what()));
        if (!e.why().isEmpty()) h.append(" — ").append(esc(e.why()));
        h.append("</li>\n");
    }

    /** One project's facts, in the words the model was given, with the text sessions wrote beside them. */
    private static void facts(StringBuilder h, ProjectFacts p, View v) {
        BriefSnapshot s = v.snapshot();
        Instant at = v.at();
        h.append("<h3>").append(esc(BriefPrompt.line(p.name(), BriefSnapshot.TITLE))).append("</h3>\n");
        if (p.summary() != null && !p.summary().isBlank()) h.append("<p>").append(esc(p.summary())).append("</p>\n");

        if (!p.goals().isEmpty()) {
            h.append("<h4>Goals</h4>\n<ul>\n");
            for (String goal : p.goals()) h.append("<li>").append(esc(goal)).append("</li>\n");
            h.append("</ul>\n");
        }
        if (!p.milestones().isEmpty()) {
            h.append("<h4>Milestones</h4>\n<ul>\n");
            for (Milestone m : p.milestones()) {
                h.append("<li>").append(esc(BriefPrompt.milestoneWords(m, v.zone()))).append("</li>\n");
            }
            h.append("</ul>\n");
        }

        int sessions = Math.max(p.sessionsTotal(), p.sessions().size());
        h.append("<h4>Sessions: ").append(sessions)
                .append(esc(BriefPrompt.shown(sessions, p.sessions().size())))
                .append("</h4>\n");
        if (!p.sessions().isEmpty()) {
            h.append("<ul>\n");
            for (SessionFacts x : p.sessions()) {
                h.append("<li>").append(esc(BriefPrompt.sessionWords(x, at)));
                if (x.branch() != null && !x.branch().isBlank()) h.append(" · on <code>").append(esc(x.branch())).append("</code>");
                if (x.intent() != null && !x.intent().isBlank()) h.append(" · ").append(esc(x.intent()));
                h.append("</li>\n");
            }
            h.append("</ul>\n");
        }

        h.append("<h4>Closed since the last brief: ").append(esc(BriefPrompt.closedWords(p)))
                .append(esc(BriefPrompt.shown(BriefPrompt.closedCount(p), p.closed().size()))).append("</h4>\n");
        if (!p.closed().isEmpty()) {
            h.append("<ul>\n");
            for (Closed c : p.closed()) {
                h.append("<li>").append(esc(BriefPrompt.closedWords(c, at)));
                if (c.intent() != null && !c.intent().isBlank()) h.append(" · ").append(esc(c.intent()));
                h.append("</li>\n");
            }
            h.append("</ul>\n");
        }

        int waiting = Math.max(p.waitingTotal(), p.waiting().size());
        h.append("<h4>Waiting on you: ").append(esc(BriefPrompt.waitingWords(waiting, p.waitingUrgent())))
                .append(esc(BriefPrompt.shown(waiting, p.waiting().size()))).append("</h4>\n");
        items(h, p.waiting(), at);

        h.append("<p>Focus: ").append(esc(BriefPrompt.focusWords(p, s.promptsTotal(), at, "you"))).append("</p>\n");
        h.append("<p>Rhythm: ").append(esc(BriefPrompt.rhythmWords(p.quiet()))).append("</p>\n");
    }

    private static void elsewhere(StringBuilder h, View v) {
        BriefSnapshot s = v.snapshot();
        StringBuilder e = new StringBuilder();
        if (!s.otherProjects().isEmpty()) {
            e.append("<p>Active projects not shown: ").append(esc(BriefPrompt.names(s.otherProjects()))).append("</p>\n");
        }
        String unconfirmed = BriefPrompt.unconfirmedWords(s, "your");
        if (unconfirmed != null) e.append("<p>").append(esc(unconfirmed)).append("</p>\n");
        int outside = Math.max(s.elsewhereTotal(), s.elsewhere().size());
        if (outside > 0) {
            e.append("<h4>Waiting on you outside these projects: ").append(outside)
                    .append(esc(BriefPrompt.shown(outside, s.elsewhere().size()))).append("</h4>\n");
            items(e, s.elsewhere(), v.at());
        }
        String tasks = BriefPrompt.tasksWords(s.tasks());
        if (tasks != null) {
            e.append("<h4>orange's tasks: ").append(esc(tasks)).append("</h4>\n");
            if (!s.tasks().needsHuman().isEmpty()) {
                e.append("<ul>\n");
                for (String title : s.tasks().needsHuman()) {
                    e.append("<li>needs a person · ").append(esc(title)).append("</li>\n");
                }
                e.append("</ul>\n");
            }
        }
        if (!e.isEmpty()) h.append("<h3>Elsewhere</h3>\n").append(e);
    }

    private static void items(StringBuilder h, List<Item> items, Instant at) {
        if (items.isEmpty()) return;
        h.append("<ul>\n");
        for (Item i : items) {
            h.append("<li>").append(esc(BriefPrompt.itemWords(i, at)));
            if (i.title() != null && !i.title().isBlank()) h.append(" · ").append(esc(i.title()));
            h.append("</li>\n");
        }
        h.append("</ul>\n");
    }

    private static String page(String body) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>orange brief</title>\n<style>\n" + STYLE + "</style>\n</head>\n<body>\n" + body
                + "</body>\n</html>\n";
    }

    /** The page declares UTF-8, so only what HTML itself reads is replaced. */
    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
