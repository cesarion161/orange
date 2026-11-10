package com.ai.orange.brief;

import static com.ai.orange.brief.BriefFixtures.brief;
import static com.ai.orange.brief.BriefFixtures.closed;
import static com.ai.orange.brief.BriefFixtures.item;
import static com.ai.orange.brief.BriefFixtures.milestone;
import static com.ai.orange.brief.BriefFixtures.milestoneIn;
import static com.ai.orange.brief.BriefFixtures.onDemand;
import static com.ai.orange.brief.BriefFixtures.project;
import static com.ai.orange.brief.BriefFixtures.quietFor;
import static com.ai.orange.brief.BriefFixtures.session;
import static com.ai.orange.brief.BriefFixtures.snapshot;
import static com.ai.orange.brief.BriefFixtures.tasks;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.brief.BriefContent.Focus;
import com.ai.orange.brief.BriefContent.Line;
import com.ai.orange.brief.BriefContent.Urgent;
import com.ai.orange.brief.BriefFixtures.ProjectBuilder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class BriefTextTest {

    static final String PAUSED = "orange is paused (plan_limit until 11:00): facts only";

    @Test
    void aFactsOnlyBriefShowsItsNoteAndARuleLinePerProject() {
        BriefSnapshot s = snapshot(
                project("orange-ai").session(session(BriefSnapshot.WORKING, "main", "x"))
                        .session(session(BriefSnapshot.ASKING, "feat/a", "y"))
                        .closed(closed("integrated", "z")).prompts(3),
                project("blog")).build();

        String text = BriefText.text(brief(s, null, PAUSED));

        assertThat(text).isEqualTo("""
                Tue 10 Nov 09:05 · since Mon 9 Nov 14:00
                orange is paused (plan_limit until 11:00): facts only

                Projects
                orange-ai — 2 sessions (1 waiting on you); 1 closed since the last brief; 3 prompts from you
                blog — nothing moved

                Waiting on you: 0 (0 urgent)""");
    }

    @Test
    void theRuleLineIsBuiltFromTheCounts() {
        assertThat(BriefText.ruleLine(project("p").build())).isEqualTo("nothing moved");
        assertThat(BriefText.ruleLine(project("p").session(session(BriefSnapshot.STOPPED, null, null)).build()))
                .isEqualTo("1 session");
        assertThat(BriefText.ruleLine(project("p").session(session(BriefSnapshot.NEEDS_PERMISSION, null, null))
                .session(session(BriefSnapshot.ASKING, null, null)).sessionsTotal(11).build()))
                .isEqualTo("11 sessions (2 waiting on you)");
        assertThat(BriefText.ruleLine(project("p").closedCounts(2, 1).prompts(1).build()))
                .isEqualTo("3 closed since the last brief; 1 prompt from you");
        // Nothing a session wrote reaches a rule line.
        assertThat(BriefText.ruleLine(project("p").session(session(BriefSnapshot.WORKING, "SECRET-BRANCH", "SECRET-INTENT"))
                .closed(closed("verified", "SECRET-WORK")).build())).doesNotContain("SECRET");
    }

    @Test
    void theModelsLineStandsBesideOrangesFacts() {
        BriefSnapshot s = snapshot(
                project("orange-ai").milestone(milestoneIn("Public beta", 21)).milestone(milestoneIn("Launch post", -3))
                        .milestone(milestoneIn("Docs", 0)).milestone(milestone("Someday", null, null, null, false, null))
                        .quiet(quietFor(7)),
                project("blog").milestone(milestoneIn("Draft", 1))).build();
        BriefContent content = new BriefContent(List.of(new Line("orange-ai", "Auth landed; the beta is on track."),
                new Line("blog", "Nothing moved.")), List.of(), List.of());

        String text = BriefText.text(brief(s, content, null));

        // The two nearest dated milestones, overdue first, and quiet: whatever the line says.
        assertThat(text).contains("orange-ai — Auth landed; the beta is on track. "
                + "[Launch post 3 days overdue; Docs due today; quiet since 2026-11-10]\n");
        assertThat(text).contains("blog — Nothing moved. [Draft due in 1 day]\n");
        assertThat(text).doesNotContain("Public beta").doesNotContain("Someday").doesNotContain("facts only");
    }

    @Test
    void aProjectTheModelLeftOutGetsItsRuleLine() {
        BriefSnapshot s = snapshot(project("orange-ai").prompts(2), project("blog").prompts(1)).build();
        BriefContent content = new BriefContent(List.of(new Line("orange-ai", "Auth landed."), new Line("blog", " ")),
                List.of(), List.of());

        assertThat(BriefText.text(brief(s, content, null))).contains("orange-ai — Auth landed.\n")
                .contains("blog — 1 prompt from you\n");
    }

    @Test
    void whatWaitsOnTheUserIsCountedFromTheFactsWhateverTheLineSays() {
        BriefSnapshot s = snapshot(project("orange-ai")
                .waiting(item("session_question", "urgent", "Which token format?"))
                .waiting(item("session_idle", "normal", "Stopped")))
                .elsewhere(item("project_proposed", "normal", "Is new-repo a project?")).build();
        BriefContent rosy = new BriefContent(List.of(new Line("orange-ai", "All done, nothing waits.")), List.of(),
                List.of());

        String text = BriefText.text(brief(s, rosy, null));

        assertThat(text).contains("orange-ai — All done, nothing waits.\n");
        assertThat(text).contains("Waiting on you: 3 (1 urgent), 1 outside these projects");
    }

    @Test
    void urgentItemsElsewhereCountAsUrgent() {
        BriefSnapshot s = snapshot(project("orange-ai").waitingCounts(12, 2))
                .elsewhere(item("node_down", "critical", "The laptop node is gone"))
                .elsewhere(item("project_proposed", "normal", "x")).elsewhereTotal(7).build();

        assertThat(BriefText.text(brief(s, null, null))).contains("Waiting on you: 19 (3 urgent), 7 outside these projects");
    }

    @Test
    void urgentAndFocusPrintInOrder() {
        BriefSnapshot s = snapshot(project("orange-ai"), project("blog")).build();
        BriefContent content = new BriefContent(
                List.of(new Line("orange-ai", "Auth landed."), new Line("blog", "Nothing moved.")),
                List.of(new Focus("orange-ai", "Answer the token question", "A session has waited since morning."),
                        new Focus("blog", "Pick the launch date", "The post depends on it."),
                        new Focus("orange-ai", "Review the migration", "It is risky.")),
                List.of(new Urgent("blog", "The domain expires tonight", "Nothing else says so.")));

        String text = BriefText.text(brief(s, content, null));

        assertThat(text).isEqualTo("""
                Tue 10 Nov 09:05 · since Mon 9 Nov 14:00

                Urgent
                ! blog: The domain expires tonight — Nothing else says so.

                Focus
                1. orange-ai: Answer the token question — A session has waited since morning.
                2. blog: Pick the launch date — The post depends on it.
                3. orange-ai: Review the migration — It is risky.

                Projects
                orange-ai — Auth landed.
                blog — Nothing moved.

                Waiting on you: 0 (0 urgent)""");
    }

    @Test
    void urgentAndFocusAreAbsentWhenEmpty() {
        BriefSnapshot s = snapshot(project("orange-ai")).build();
        BriefContent content = new BriefContent(List.of(new Line("orange-ai", "Auth landed.")), List.of(), List.of());

        assertThat(BriefText.text(brief(s, content, null))).doesNotContain("Urgent").doesNotContain("Focus");
        assertThat(BriefText.text(brief(s, null, PAUSED))).doesNotContain("Urgent").doesNotContain("Focus");
        assertThat(BriefText.html(brief(s, content, null))).doesNotContain("<h2>Urgent</h2>").doesNotContain("<h2>Focus</h2>");
    }

    @Test
    void tasksAndProjectsOutsideTheBriefFollow() {
        BriefSnapshot s = snapshot(project("orange-ai")).otherProjects("attic", "cellar").proposedAndPaused(2, 1)
                .tasks(tasks(2, List.of("Migrate the queue"), "running", 3, "needs_human", 1)).build();

        assertThat(BriefText.text(brief(s, null, null))).endsWith("""
                Waiting on you: 0 (0 urgent)
                orange's tasks: 3 running, 1 needs a person; 2 integrated since the last brief
                Active projects not shown: attic, cellar
                2 projects await your confirmation""");
    }

    @Test
    void anOnDemandBriefSaysSo() {
        BriefSnapshot s = snapshot(project("orange-ai")).build();

        assertThat(BriefText.text(onDemand(s, null, null))).startsWith("Tue 10 Nov 09:05 · since Mon 9 Nov 14:00 (on demand)\n");
        assertThat(BriefText.text(brief(s, null, null))).doesNotContain("on demand");
    }

    @Test
    void aPushIsCutToItsLimit() {
        List<ProjectBuilder> many = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < BriefSnapshot.MAX_PROJECTS; i++) {
            many.add(project("project-" + i));
            lines.add(new Line("project-" + i, "x".repeat(BriefContent.MAX_STATUS)));
        }
        Brief brief = brief(snapshot(many.toArray(ProjectBuilder[]::new)).build(),
                new BriefContent(lines, List.of(), List.of()), null);

        String push = BriefText.push(brief);

        assertThat(BriefText.text(brief).length()).isGreaterThan(BriefText.MAX_PUSH);
        assertThat(push.length()).isLessThanOrEqualTo(BriefText.MAX_PUSH);
        assertThat(push).endsWith("…").startsWith("Tue 10 Nov 09:05");
        assertThat(BriefText.text(brief)).startsWith(push.substring(0, push.length() - 1));
    }

    @Test
    void aShortPushIsTheText() {
        Brief brief = brief(snapshot(project("orange-ai")).build(), null, PAUSED);

        assertThat(BriefText.push(brief)).isEqualTo(BriefText.text(brief)).doesNotEndWith("…");
    }

    @Test
    void theTitleSaysWhenHowManyAndWhoWroteIt() {
        BriefSnapshot three = snapshot(project("a"), project("b"), project("c")).build();
        BriefSnapshot one = snapshot(project("a")).build();
        BriefContent content = new BriefContent(List.of(new Line("a", "Moved.")), List.of(), List.of());

        // The slot (09:00), not when the facts were read (09:05).
        assertThat(BriefText.title(brief(three, content, null))).isEqualTo("orange brief · 09:00 · 3 projects");
        assertThat(BriefText.title(brief(three, null, PAUSED))).isEqualTo("orange brief · 09:00 · 3 projects · facts only");
        assertThat(BriefText.title(onDemand(one, content, null))).isEqualTo("orange brief · 09:05 · 1 project");
    }

    @Test
    void timesAreShownInTheSnapshotsZone() {
        BriefSnapshot s = snapshot(project("a")).zone("America/New_York").build();

        Brief brief = brief(s, null, null);

        assertThat(BriefText.title(brief)).startsWith("orange brief · 03:00 · ");
        assertThat(BriefText.text(brief)).startsWith("Tue 10 Nov 03:05 · since Mon 9 Nov 08:00\n");
    }

    @Test
    void thePageEscapesWhatSessionsAndTheModelWrote() {
        BriefSnapshot s = snapshot(project("orange-ai").summary("<b>bold</b> & more").goals("<img src=x onerror=alert(1)>")
                .milestone(milestone("<img src=m>", "2026-12-01T12:00+01:00", 21, 30, false, 4))
                .session(session(BriefSnapshot.ASKING, "feat/<img src=b>", "<img src=x>"))
                .closed(closed("integrated", "<img src=c>"))
                .waiting(item("session_question", "urgent", "<img src=w>")))
                .otherProjects("<img src=o>")
                .elsewhere(item("project_proposed", "normal", "<img src=e>"))
                .tasks(tasks(0, List.of("<img src=t>"), "needs_human", 1)).build();
        BriefContent content = new BriefContent(List.of(new Line("orange-ai", "<script>alert(1)</script>")),
                List.of(new Focus("orange-ai", "<script>focus()</script>", "<img src=f>")),
                List.of(new Urgent("orange-ai", "<script>urgent()</script>", "\"quoted\" & 'single'")));

        String html = BriefText.html(brief(s, content, "<script>note()</script>"));

        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<b>bold");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;img src=x&gt;")
                .contains("&lt;script&gt;note()&lt;/script&gt;").contains("&lt;b&gt;bold&lt;/b&gt; &amp; more")
                .contains("&quot;quoted&quot; &amp; &#39;single&#39;");
        for (String field : List.of("m", "b", "c", "w", "o", "e", "t", "f")) {
            assertThat(html).as(field).contains("&lt;img src=" + field + "&gt;");
        }
    }

    @Test
    void aProjectNameIsEscapedToo() {
        BriefSnapshot s = snapshot(project("<script>name()</script>")).build();

        assertThat(BriefText.html(brief(s, null, null))).doesNotContain("<script")
                .contains("&lt;script&gt;name()&lt;/script&gt;");
    }

    @Test
    void thePageIsOneDocumentWithNoScriptAndNothingFromElsewhere() {
        BriefSnapshot s = snapshot(project("orange-ai").summary("The coordinator").goals("Ship the beta")
                .milestone(milestoneIn("Public beta", 21))
                .session(session(BriefSnapshot.ASKING, "feat/auth", "Add token auth"))
                .closed(closed("integrated", "Fix the reaper"))
                .waiting(item("session_question", "urgent", "Which token format?")).prompts(14),
                project("blog").quiet(quietFor(9)))
                .promptsTotal(20).elsewhere(item("project_proposed", "normal", "Is new-repo a project?"))
                .tasks(tasks(1, List.of("Migrate the queue"), "needs_human", 1)).build();
        BriefContent content = new BriefContent(
                List.of(new Line("orange-ai", "Auth landed."), new Line("blog", "Nothing moved.")),
                List.of(new Focus("orange-ai", "Answer the token question", "A session waits.")), List.of());

        String html = BriefText.html(brief(s, content, null));

        assertThat(html).startsWith("<!doctype html>\n").endsWith("</html>\n")
                .contains("<meta charset=\"utf-8\">").contains("<meta name=\"viewport\"")
                .contains("<title>orange brief</title>").contains("<style>");
        assertThat(html.toLowerCase()).doesNotContain("<script").doesNotContain("<img").doesNotContain("<link")
                .doesNotContain("http://").doesNotContain("https://").doesNotContain("javascript:")
                .doesNotContain(" onclick").doesNotContain("@import").doesNotContain("url(");
        // The brief, as in the text…
        assertThat(html).contains("<h1>orange brief · 09:00 · 2 projects</h1>")
                .contains("<h2>Focus</h2>").contains("<li><b>orange-ai</b>: Answer the token question — A session waits.</li>")
                .contains("<li><b>orange-ai</b> — Auth landed. <span class=\"facts\">[Public beta due in 21 days]</span></li>")
                .contains("<li><b>blog</b> — Nothing moved. <span class=\"facts\">[quiet since 2026-11-10]</span></li>")
                .contains("<p>Waiting on you: 2 (1 urgent), 1 outside these projects</p>")
                .contains("<p>orange&#39;s tasks: 1 needs a person; 1 integrated since the last brief</p>");
        // …then the facts it was written from.
        String facts = html.substring(html.indexOf("<h2>Facts</h2>"));
        assertThat(facts).contains("<h3>orange-ai</h3>").contains("<p>The coordinator</p>").contains("<li>Ship the beta</li>")
                .contains("<li>Public beta: due 2026-11-10, in 21 days; 30% of its time has passed; 0 pieces of work closed "
                        + "in the project since it was set (not linked to this milestone)</li>")
                .contains("<li>asks the user something; waiting 3 h · on <code>feat/auth</code> · Add token auth</li>")
                .contains("<h4>Closed since the last brief: 1 integrated</h4>")
                .contains("<li>integrated 2 h ago · Fix the reaper</li>")
                .contains("<h4>Waiting on you: 1 (1 urgent)</h4>")
                .contains("<li>urgent session_question, open 3 h · Which token format?</li>")
                .contains("<p>Focus: 14 of your 20 prompts since the last brief; last prompt 12 min ago</p>")
                .contains("<p>Rhythm: active on 9 of the last 60 days, last on 2026-11-10; usual gap 1 day, longest 5: "
                        + "QUIET: nothing for 9 days</p>")
                .contains("<h3>Elsewhere</h3>")
                .contains("<li>normal project_proposed, open 3 h · Is new-repo a project?</li>")
                .contains("<li>needs a person · Migrate the queue</li>");
    }

    @Test
    void theEmptyPageSaysThereIsNoBriefYet() {
        assertThat(BriefText.empty()).startsWith("<!doctype html>\n").contains("<title>orange brief</title>")
                .contains("No brief yet.").doesNotContain("<script");
    }

    @Test
    void aBriefWithAnEmptyOrBrokenSnapshotStillRenders() {
        OffsetDateTime slot = OffsetDateTime.parse(BriefFixtures.SLOT);
        for (String stored : List.of("{}", "[1,2]", "not json", "{\"projects\":[{}],\"elsewhere\":[{}],\"tasks\":{}}")) {
            Brief brief = brief(slot, JSONB.valueOf(stored), JSONB.valueOf("{\"projects\":7}"), null);

            assertThat(BriefText.text(brief)).as(stored).contains("Waiting on you: ");
            assertThat(BriefText.title(brief)).as(stored).startsWith("orange brief · ");
            assertThat(BriefText.push(brief)).as(stored).isNotBlank();
            assertThat(BriefText.html(brief)).as(stored).contains("<h2>Facts</h2>");
        }
        Brief empty = brief(slot, JSONB.valueOf("{}"), null, null);
        assertThat(BriefText.text(empty)).isEqualTo("Tue 10 Nov 08:05\n\nWaiting on you: 0 (0 urgent)");
        assertThat(BriefText.title(empty)).isEqualTo("orange brief · 08:00 · 0 projects · facts only");
    }
}
