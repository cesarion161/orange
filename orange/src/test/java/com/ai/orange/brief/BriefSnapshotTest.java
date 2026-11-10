package com.ai.orange.brief;

import static com.ai.orange.brief.BriefFixtures.closed;
import static com.ai.orange.brief.BriefFixtures.item;
import static com.ai.orange.brief.BriefFixtures.milestone;
import static com.ai.orange.brief.BriefFixtures.milestoneIn;
import static com.ai.orange.brief.BriefFixtures.project;
import static com.ai.orange.brief.BriefFixtures.quietFor;
import static com.ai.orange.brief.BriefFixtures.session;
import static com.ai.orange.brief.BriefFixtures.snapshot;
import static com.ai.orange.brief.BriefFixtures.tasks;
import static com.ai.orange.brief.BriefFixtures.tooNew;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

/** The snapshot as a value: it survives storage, says whether there is news, and names session states. */
class BriefSnapshotTest {

    static final Duration STALE = Duration.ofHours(12);

    /** A snapshot with every part filled in. */
    static BriefSnapshot full() {
        return snapshot(
                project("orange-ai").summary("The coordinator").goals("Ship Phase D", "Keep the tests green")
                        .milestone(milestone("Beta", "2026-12-01T23:59+01:00", 21, 30, false, 4))
                        .milestone(milestone("Someday", null, null, null, false, null))
                        .session(session("working", "feat/x", "Build the brief"))
                        .session(session("asking", null, null))
                        .sessionsTotal(11)
                        .closed(closed("integrated", "Fix the reaper"))
                        .closed(closed("verified", "Tidy the docs"))
                        .waiting(item("session_question", "urgent", "orange-ai asks: merge now?"))
                        .waiting(item("session_idle", "normal", "orange-ai stopped"))
                        .prompts(14),
                project("blog").quiet(quietFor(7)))
                .otherProjects("a", "b").proposedAndPaused(1, 2).promptsTotal(20)
                .elsewhere(item("project_proposed", "normal", "New project: x"))
                .tasks(tasks(2, List.of("Rotate the key"), "running", 3, "needs_human", 1))
                .build();
    }

    static BriefSnapshot calm() {
        return snapshot(project("orange-ai"), project("blog").quiet(tooNew())).build();
    }

    @Test
    void aStoredSnapshotReadsBackEqual() throws Exception {
        BriefSnapshot original = full();
        JSONB stored = original.toJsonb();

        assertThat(BriefSnapshot.parse(stored)).isEqualTo(original);
        JsonNode json = Json.mapper().readTree(stored.data());
        assertThat(json.path("projects").get(0).path("name").asText()).isEqualTo("orange-ai");
        assertThat(json.path("projects").get(0).path("milestones").get(0).path("daysLeft").asInt()).isEqualTo(21);
        assertThat(json.path("projects").get(1).path("quiet").path("verdict").asText()).isEqualTo("quiet");
        assertThat(json.path("tasks").path("byStatus").path("running").asInt()).isEqualTo(3);
        // Only the components are stored: no derived property sneaks in.
        assertThat(json.fieldNames()).toIterable().containsExactly("at", "since", "zone", "projects",
                "otherProjects", "proposedProjects", "pausedProjects", "promptsTotal", "elsewhere", "elsewhereTotal",
                "tasks");
        assertThat(json.path("projects").get(1).path("quiet").fieldNames()).toIterable().containsExactly("verdict",
                "activeDays", "lastActiveDay", "daysSince", "usualGap", "longestGap", "cadenceDays");
        assertThat(original.projectNames()).containsExactly("orange-ai", "blog");
    }

    @Test
    void anEmptyOrPartialSnapshotStillReads() {
        BriefSnapshot empty = BriefSnapshot.parse(JSONB.valueOf("{}"));
        assertThat(empty.projects()).isEmpty();
        assertThat(empty.otherProjects()).isEmpty();
        assertThat(empty.elsewhere()).isEmpty();
        assertThat(empty.tasks().byStatus()).isEmpty();
        assertThat(empty.tasks().needsHuman()).isEmpty();
        assertThat(empty.projectNames()).isEmpty();
        assertThat(empty.hasNews(empty)).isFalse();

        // A snapshot written by an older orange: fields are missing, others are unknown.
        BriefSnapshot partial = BriefSnapshot.parse(JSONB.valueOf(
                "{\"projects\": [{\"name\": \"blog\", \"mood\": \"good\", \"milestones\": [null]}], \"extra\": 1}"));
        ProjectFacts blog = partial.projects().get(0);
        assertThat(blog.goals()).isEmpty();
        assertThat(blog.milestones()).isEmpty();
        assertThat(blog.sessions()).isEmpty();
        assertThat(blog.closed()).isEmpty();
        assertThat(blog.waiting()).isEmpty();
        assertThat(blog.quiet().verdict()).isEqualTo(Quiet.UNKNOWN);
        assertThat(partial.hasNews(partial)).isFalse();
    }

    @Test
    void whatIsNoSnapshotIsRefused() {
        assertThatThrownBy(() -> BriefSnapshot.parse(JSONB.valueOf("[1, 2]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BriefSnapshot.parse(JSONB.valueOf("{\"projects\": \"all of them\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BriefSnapshot.parse(JSONB.valueOf("not json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BriefSnapshot.parse(JSONB.valueOf("null")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BriefSnapshot.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFirstBriefIsAlwaysNews() {
        assertThat(calm().hasNews(null)).isTrue();
        assertThat(calm().hasNews(calm())).isFalse();
    }

    @Test
    void eachOfTheseIsNewsByItself() {
        BriefSnapshot before = calm();
        assertThat(snapshot(project("orange-ai").waiting(item("session_question", "normal", "q"))).build()
                .hasNews(before)).as("an open item").isTrue();
        assertThat(snapshot(project("orange-ai").waitingCounts(9, 0)).build().hasNews(before))
                .as("open items beyond the listed ones").isTrue();
        assertThat(snapshot(project("orange-ai").prompts(1)).build().hasNews(before)).as("a prompt").isTrue();
        assertThat(snapshot(project("orange-ai").closed(closed("verified", "w"))).build().hasNews(before))
                .as("closed work").isTrue();
        assertThat(snapshot(project("orange-ai").closedCounts(1, 0)).build().hasNews(before))
                .as("closed work beyond the listed").isTrue();
        assertThat(snapshot(project("orange-ai")).elsewhere(item("project_proposed", "normal", "New")).build()
                .hasNews(before)).as("an item elsewhere").isTrue();
        assertThat(snapshot(project("orange-ai")).tasks(tasks(1, List.of())).build().hasNews(before))
                .as("an integrated task").isTrue();
        assertThat(snapshot(project("orange-ai")).tasks(tasks(0, List.of("Rotate the key"), "needs_human", 1))
                .build().hasNews(before)).as("a task that needs a person").isTrue();
        assertThat(snapshot(project("orange-ai")).tasks(tasks(0, List.of(), "needs_human", 1)).build()
                .hasNews(before)).as("a task that needs a person, by count").isTrue();
        assertThat(snapshot(project("orange-ai").milestone(milestoneIn("Beta", -1))).build().hasNews(before))
                .as("an overdue milestone").isTrue();
        assertThat(snapshot(project("orange-ai").milestone(milestone("Beta", "2026-11-10T08:00+01:00", 0, 100,
                true, 0))).build().hasNews(before)).as("a milestone overdue today").isTrue();
        assertThat(snapshot(project("orange-ai").milestone(milestoneIn("Beta", 2))).build().hasNews(before))
                .as("a milestone two days away").isTrue();
        assertThat(snapshot(project("orange-ai"), project("blog").quiet(quietFor(7))).build().hasNews(before))
                .as("a project that went quiet").isTrue();
    }

    @Test
    void noneOfTheseIsNews() {
        BriefSnapshot before = calm();
        assertThat(snapshot(project("orange-ai")
                .goals("Ship it").summary("The coordinator")
                .milestone(milestoneIn("Beta", 3))
                .milestone(milestone("Someday", null, null, null, false, null))
                .session(session("working", "feat/x", "Build the brief")),
                project("blog").quiet(tooNew()))
                .otherProjects("a").proposedAndPaused(1, 1)
                .tasks(tasks(0, List.of(), "running", 2, "pending", 4)).build().hasNews(before))
                .as("work under way, a milestone three days away, tasks running").isFalse();
    }

    @Test
    void aProjectThatWasAlreadyQuietIsNotNewsAgain() {
        BriefSnapshot quietBefore = snapshot(project("orange-ai"), project("blog").quiet(quietFor(7))).build();
        BriefSnapshot stillQuiet = snapshot(project("orange-ai"), project("blog").quiet(quietFor(8))).build();
        assertThat(stillQuiet.hasNews(quietBefore)).isFalse();

        // Renamed since: it is the same project by its id.
        BriefSnapshot renamed = snapshot(project("orange-ai"),
                project("journal").id(quietBefore.projects().get(1).id()).quiet(quietFor(8))).build();
        assertThat(renamed.hasNews(quietBefore)).isFalse();
        // A quiet project the last brief did not have is news.
        BriefSnapshot another = snapshot(project("orange-ai"), project("notes").quiet(quietFor(8))).build();
        assertThat(another.hasNews(quietBefore)).isTrue();
    }

    @Test
    void sessionStatesInTheBriefsWords() {
        Duration idle = Duration.ofMinutes(5);
        assertThat(BriefSnapshot.state("judging", null, idle, STALE)).isEqualTo("checking");
        assertThat(BriefSnapshot.state("verifying", null, idle, STALE)).isEqualTo("checking");
        assertThat(BriefSnapshot.state("closed", null, idle, STALE)).isEqualTo("closed");
        assertThat(BriefSnapshot.state("stopped", "question", idle, STALE)).isEqualTo("asking");
        assertThat(BriefSnapshot.state("waiting_on_user", "permission", idle, STALE)).isEqualTo("needs_permission");
        assertThat(BriefSnapshot.state("stopped", "finished", idle, STALE)).isEqualTo("stopped");
        assertThat(BriefSnapshot.state("stopped", null, idle, STALE)).isEqualTo("stopped");
        assertThat(BriefSnapshot.state("active", null, idle, STALE)).isEqualTo("working");
        assertThat(BriefSnapshot.state("active", null, STALE, STALE)).as("exactly the limit").isEqualTo("working");
        assertThat(BriefSnapshot.state("active", null, Duration.ofHours(13), STALE)).isEqualTo("silent");
        assertThat(BriefSnapshot.state("waiting_on_user", null, idle, STALE)).isEqualTo("waiting_on_user");
        assertThat(BriefSnapshot.state("ended", null, idle, STALE)).isEqualTo("ended");
        // Only a session that says it is working can be silent.
        assertThat(BriefSnapshot.state("stopped", "question", Duration.ofHours(13), STALE)).isEqualTo("asking");
    }

    @Test
    void aTextBecomesOneLineOfBoundedLength() {
        assertThat(BriefSnapshot.one(null, 10)).isNull();
        assertThat(BriefSnapshot.one("  Ship\n\n  the\tbrief \r\n", 200)).isEqualTo("Ship the brief");
        assertThat(BriefSnapshot.one("exactly ten", 11)).isEqualTo("exactly ten");
        assertThat(BriefSnapshot.one("one two three", 8)).isEqualTo("one two…");
        assertThat(BriefSnapshot.one("one two three", 5)).as("no space before the cut mark").isEqualTo("one…");
        assertThat(BriefSnapshot.one("x".repeat(500), 200)).hasSize(200).endsWith("x…");
        assertThat(BriefSnapshot.one("   ", 10)).isEmpty();
        // Line ends that are not \n: a line separator, a next-line control, a vertical tab.
        assertThat(BriefSnapshot.one("a ## Project: b\u0085c\u000Bd e", 200))
                .isEqualTo("a ## Project: b c d e");
        // Nothing unseen stays: a bidi override, a zero-width space, an escape.
        assertThat(BriefSnapshot.one("a‮b​c\u001Bd ⁦e⁩", 200)).isEqualTo("a b c d e");
        // Never half of a character.
        assertThat(BriefSnapshot.one("ab🍊cd", 4)).isEqualTo("ab…");
    }
}
