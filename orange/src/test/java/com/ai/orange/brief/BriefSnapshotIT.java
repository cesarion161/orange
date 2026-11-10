package com.ai.orange.brief;

import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;
import static com.ai.orange.db.jooq.Tables.BRIEFS;
import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.SESSION_EVENTS;
import static com.ai.orange.db.jooq.Tables.SESSION_WORK;
import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefSnapshot.Item;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.brief.BriefSnapshot.SessionFacts;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.SpaceService.Author;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionService;
import com.ai.orange.session.SessionWork;
import com.ai.orange.session.SessionWorkRepository;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The brief's facts, read from a database this test owns: which projects get a line, their goals,
 * milestones, sessions, closed work, open items, the user's focus, the rhythm, and orange's tasks.
 */
@SpringBootTest(properties = "orange.test.context=brief-snapshot")
@Import(TestcontainersConfiguration.class)
class BriefSnapshotIT {

    @Autowired BriefSnapshots snapshots;
    @Autowired ProjectService projects;
    @Autowired SessionService sessions;
    @Autowired SpaceService space;
    @Autowired AttentionService attention;
    @Autowired SessionWorkRepository work;
    @Autowired TaskService tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired DSLContext dsl;

    /** The moment the test reads its facts at, and the start of what its brief covers. */
    final ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    final OffsetDateTime since = now.toOffsetDateTime().minusHours(1);

    @BeforeEach
    void emptyTheDatabase() {
        dsl.deleteFrom(BRIEFS).execute();
        dsl.deleteFrom(ATTENTION_ITEMS).execute();
        dsl.deleteFrom(SESSIONS).execute();
        dsl.deleteFrom(PROJECTS).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** Reads the facts a moment from now, so everything the test just did lies before it. */
    BriefSnapshot read() {
        return snapshots.read(since, ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(1));
    }

    static String repo(String name) {
        return "/tmp/brief-" + name;
    }

    /** An active project whose repo is {@link #repo}. */
    Project active(String name) {
        Project p = projects.create(name, null);
        projects.addRepo(p.id(), "local", repo(name));
        return p;
    }

    Session event(String sessionId, String type, String repoRoot, String field, String value) {
        ObjectNode e = Json.mapper().createObjectNode().put("event_id", UUID.randomUUID().toString())
                .put("harness", "claude-code").put("session_id", sessionId).put("type", type)
                .put("cwd", repoRoot).put("repo_root", repoRoot).put("branch", "main");
        if (field != null) e.putObject("payload").put(field, value);
        return sessions.apply(NeutralEvent.parse(e));
    }

    Session prompt(String sessionId, String project, String text) {
        return event(sessionId, NeutralEvent.USER_PROMPT, repo(project), "prompt", text);
    }

    Session stop(String sessionId, String project, String lastMessage) {
        return event(sessionId, NeutralEvent.STOPPED, repo(project), "last_message", lastMessage);
    }

    static String sid() {
        return "brief-" + UUID.randomUUID();
    }

    void heardFrom(Session s, OffsetDateTime at) {
        dsl.update(SESSIONS).set(SESSIONS.LAST_EVENT_AT, at).where(SESSIONS.ID.eq(s.id())).execute();
    }

    List<Long> eventIds(Session s) {
        return dsl.select(SESSION_EVENTS.ID).from(SESSION_EVENTS).where(SESSION_EVENTS.SESSION_ID.eq(s.id()))
                .orderBy(SESSION_EVENTS.ID.asc()).fetch(SESSION_EVENTS.ID);
    }

    void received(long eventId, OffsetDateTime at) {
        dsl.update(SESSION_EVENTS).set(SESSION_EVENTS.RECEIVED_AT, at).where(SESSION_EVENTS.ID.eq(eventId)).execute();
    }

    static ProjectFacts facts(BriefSnapshot s, String name) {
        return s.projects().stream().filter(p -> name.equals(p.name())).findFirst().orElseThrow();
    }

    // ─────────────────────────── projects ───────────────────────────

    @Test
    void anEmptyDatabaseGivesAnEmptySnapshot() {
        BriefSnapshot s = read();

        assertThat(s.projects()).isEmpty();
        assertThat(s.otherProjects()).isEmpty();
        assertThat(s.proposedProjects()).isZero();
        assertThat(s.pausedProjects()).isZero();
        assertThat(s.promptsTotal()).isZero();
        assertThat(s.elsewhere()).isEmpty();
        assertThat(s.elsewhereTotal()).isZero();
        assertThat(s.tasks().byStatus()).isEmpty();
        assertThat(s.tasks().integratedSince()).isZero();
        assertThat(s.tasks().needsHuman()).isEmpty();
        assertThat(s.zone()).isEqualTo("Z");
        assertThat(OffsetDateTime.parse(s.since()).toInstant()).isEqualTo(since.toInstant());
        assertThat(OffsetDateTime.parse(s.at())).isAfter(since);
        assertThat(BriefSnapshot.parse(s.toJsonb())).isEqualTo(s);
    }

    @Test
    void onlyActiveProjectsGetFacts() {
        Project alpha = projects.create("alpha", "  The first\n project ");
        projects.addRepo(alpha.id(), "local", repo("alpha"));
        // A session in a repo orange does not know proposes a project.
        Session stranger = prompt(sid(), "unknown", "What is this repo?");
        Project paused = projects.create("on-hold", null);
        projects.update(paused.id(), null, Project.PAUSED, null);
        Project archived = projects.create("old", null);
        projects.update(archived.id(), null, Project.ARCHIVED, null);

        BriefSnapshot s = read();

        assertThat(s.projectNames()).containsExactly("alpha");
        ProjectFacts facts = s.projects().get(0);
        assertThat(facts.id()).isEqualTo(alpha.id().toString());
        assertThat(facts.summary()).isEqualTo("The first project");
        assertThat(facts.sessions()).isEmpty();
        assertThat(facts.quiet().verdict()).isEqualTo(Quiet.UNKNOWN);
        assertThat(s.otherProjects()).isEmpty();
        assertThat(s.proposedProjects()).isEqualTo(1);
        assertThat(s.pausedProjects()).isEqualTo(1);
        // The proposed project's question is waiting, but not under a project of the brief.
        assertThat(stranger.projectId()).isNotNull();
        assertThat(s.elsewhere()).extracting(Item::kind).containsExactly(AttentionItem.PROJECT_PROPOSED);
        assertThat(s.elsewhereTotal()).isEqualTo(1);
        assertThat(s.promptsTotal()).as("a prompt outside the projects still counts in the total").isEqualTo(1);
        assertThat(facts.prompts()).isZero();
    }

    @Test
    void theTwelveMostRecentlyActiveProjectsGetFactsAndTheRestAreNamed() {
        for (int i = 1; i <= 13; i++) active(String.format("p%02d", i));
        // Without a session they go by name; one that was just heard from comes first.
        prompt(sid(), "p13", "Carry on");

        BriefSnapshot s = read();

        assertThat(s.projects()).hasSize(BriefSnapshot.MAX_PROJECTS);
        assertThat(s.projectNames()).startsWith("p13", "p01", "p02").endsWith("p10", "p11").doesNotContain("p12");
        assertThat(s.otherProjects()).containsExactly("p12");
    }

    // ─────────────────────────── goals and milestones ───────────────────────────

    @Test
    void activeGoalsOfTheProjectAreListed() {
        Project p = active("alpha");
        Session s1 = prompt(sid(), "alpha", "Work");
        SpaceItem first = space.add(p.id(), SpaceItem.GOAL, "Ship it", Author.USER, null, null, null);
        space.add(p.id(), SpaceItem.GOAL, "Ship it well", Author.USER, null, null, first.id());
        SpaceItem finished = space.add(p.id(), SpaceItem.GOAL, "Write the spec", Author.USER, null, null, null);
        space.done(finished.id());
        space.add(p.id(), SpaceItem.GOAL, "Only for this session", Author.USER, null, s1.id(), null);
        space.add(p.id(), SpaceItem.GOAL, "An agent's idea", Author.agent("run-1"), null, null, null);
        space.add(p.id(), SpaceItem.NOTE, "A note is no goal", Author.USER, null, null, null);
        space.add(p.id(), SpaceItem.GOAL, "Line one\n\nline two   " + "x".repeat(500), Author.USER, null, null, null);

        List<String> goals = facts(read(), "alpha").goals();

        assertThat(goals).hasSize(2);
        // Newest first; one line; cut.
        assertThat(goals.get(0)).startsWith("Line one line two xxx").hasSize(BriefSnapshot.TEXT).endsWith("x…")
                .doesNotContain("\n");
        assertThat(goals.get(1)).isEqualTo("Ship it well");
    }

    @Test
    void atMostFiveGoalsTheNewestOnes() {
        Project p = active("alpha");
        for (int i = 1; i <= 7; i++) {
            SpaceItem goal = space.add(p.id(), SpaceItem.GOAL, "Goal " + i, Author.USER, null, null, null);
            dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.CREATED_AT, now.toOffsetDateTime().minusDays(10 - i))
                    .where(SPACE_ITEMS.ID.eq(goal.id())).execute();
        }

        assertThat(facts(read(), "alpha").goals()).containsExactly("Goal 7", "Goal 6", "Goal 5", "Goal 4", "Goal 3");
    }

    @Test
    void aMilestoneHasItsDaysLeftItsTimeGoneAndTheWorkClosedSince() {
        Project p = active("alpha");
        OffsetDateTime at = now.toOffsetDateTime();
        SpaceItem beta = space.add(p.id(), SpaceItem.MILESTONE, "Beta", Author.USER, at.plusDays(3), null, null);
        SpaceItem past = space.add(p.id(), SpaceItem.MILESTONE, "Alpha", Author.USER, at.minusDays(1), null, null);
        space.add(p.id(), SpaceItem.MILESTONE, "Someday", Author.USER, null, null, null);
        dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.CREATED_AT, at.minusDays(7)).where(SPACE_ITEMS.ID.eq(beta.id()))
                .execute();
        dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.CREATED_AT, at.minusDays(5)).where(SPACE_ITEMS.ID.eq(past.id()))
                .execute();
        // Two pieces of work closed: one before "Alpha" was set, both after "Beta" was.
        Session s1 = prompt(sid(), "alpha", "First piece");
        work.close(s1.id(), SessionWork.INTEGRATED, "abc", "main", "main", null);
        Session s2 = prompt(sid(), "alpha", "Second piece");
        work.close(s2.id(), SessionWork.VERIFIED, "def", "main", "main", null);
        dsl.update(SESSION_WORK).set(SESSION_WORK.CLOSED_AT, at.minusDays(6))
                .where(SESSION_WORK.SESSION_ID.eq(s1.id())).execute();

        List<Milestone> milestones = facts(snapshots.read(since, now), "alpha").milestones();

        // The nearest date first, the undated one last.
        assertThat(milestones).extracting(Milestone::text).containsExactly("Alpha", "Beta", "Someday");
        Milestone alpha = milestones.get(0);
        assertThat(alpha.overdue()).isTrue();
        assertThat(alpha.daysLeft()).isEqualTo(-1);
        assertThat(alpha.elapsedPercent()).isEqualTo(100);
        assertThat(alpha.closedSince()).isEqualTo(1);
        Milestone b = milestones.get(1);
        assertThat(b.overdue()).isFalse();
        assertThat(b.daysLeft()).isEqualTo(3);
        assertThat(b.elapsedPercent()).isEqualTo(70);
        assertThat(b.closedSince()).isEqualTo(2);
        assertThat(OffsetDateTime.parse(b.dueAt()).toInstant()).isEqualTo(at.plusDays(3).toInstant());
        Milestone someday = milestones.get(2);
        assertThat(someday.dueAt()).isNull();
        assertThat(someday.daysLeft()).isNull();
        assertThat(someday.elapsedPercent()).isNull();
        assertThat(someday.overdue()).isFalse();
        assertThat(someday.closedSince()).isNull();
    }

    @Test
    void daysLeftAreCalendarDaysInTheUsersZone() {
        Project p = active("alpha");
        // Due at 23:30 UTC tomorrow: for someone two hours east that is the day after tomorrow.
        LocalDate today = now.toLocalDate();
        OffsetDateTime due = today.plusDays(1).atTime(23, 30).atOffset(ZoneOffset.UTC);
        space.add(p.id(), SpaceItem.MILESTONE, "Beta", Author.USER, due, null, null);
        ZonedDateTime noonUtc = today.atTime(12, 0).atZone(ZoneOffset.UTC);

        assertThat(facts(snapshots.read(since, noonUtc), "alpha").milestones().get(0).daysLeft()).isEqualTo(1);
        assertThat(facts(snapshots.read(since, noonUtc.withZoneSameInstant(ZoneOffset.ofHours(2))), "alpha")
                .milestones().get(0).daysLeft()).isEqualTo(2);
    }

    // ─────────────────────────── sessions ───────────────────────────

    @Test
    void sessionsAreListedWithTheirState() {
        active("alpha");
        OffsetDateTime at = now.toOffsetDateTime();
        Session silent = prompt(sid(), "alpha", "Refactor the reaper");
        heardFrom(silent, at.minusHours(13));
        Session working = prompt(sid(), "alpha", "Build  the\nbrief");
        Session asking = prompt(sid(), "alpha", "Fix the build");
        asking = stop(asking.externalId(), "alpha", "Shall I continue?");
        Session ended = prompt(sid(), "alpha", "Done long ago");
        event(ended.externalId(), NeutralEvent.SESSION_ENDED, repo("alpha"), "reason", "exit");
        Session old = prompt(sid(), "alpha", "Forgotten");
        heardFrom(old, at.minusDays(8));

        ProjectFacts facts = facts(read(), "alpha");

        assertThat(facts.sessionsTotal()).isEqualTo(3);
        // The latest heard from first.
        assertThat(facts.sessions()).extracting(SessionFacts::state)
                .containsExactly(BriefSnapshot.ASKING, BriefSnapshot.WORKING, BriefSnapshot.SILENT);
        SessionFacts first = facts.sessions().get(0);
        assertThat(first.intent()).isEqualTo("Fix the build");
        assertThat(first.branch()).isEqualTo("main");
        assertThat(first.waitingSince()).isNotNull();
        assertThat(OffsetDateTime.parse(first.lastEventAt()).toInstant())
                .isEqualTo(asking.lastEventAt().toInstant());
        SessionFacts second = facts.sessions().get(1);
        assertThat(second.intent()).isEqualTo("Build the brief");
        assertThat(second.waitingSince()).isNull();
        assertThat(facts.sessions().get(2).intent()).isEqualTo("Refactor the reaper");
        assertThat(working.status()).isEqualTo(Session.ACTIVE);
    }

    @Test
    void atMostEightSessionsAreListedAndAllAreCounted() {
        active("alpha");
        for (int i = 0; i < 10; i++) prompt(sid(), "alpha", "Piece " + i);

        ProjectFacts facts = facts(read(), "alpha");

        assertThat(facts.sessions()).hasSize(BriefSnapshot.MAX_SESSIONS);
        assertThat(facts.sessionsTotal()).isEqualTo(10);
        assertThat(facts.sessions().get(0).intent()).isEqualTo("Piece 9");
        assertThat(facts.prompts()).isEqualTo(10);
    }

    // ─────────────────────────── closed work ───────────────────────────

    @Test
    void workClosedSinceTheLastBriefIsListedWithItsResult() {
        active("alpha");
        Session s1 = prompt(sid(), "alpha", "Fix the\nreaper");
        work.close(s1.id(), SessionWork.INTEGRATED, "abc", "feat", "main", "rebased and pushed");
        Session s2 = prompt(sid(), "alpha", "Tidy the docs");
        work.close(s2.id(), SessionWork.VERIFIED, "def", "docs", "main", null);
        Session s3 = prompt(sid(), "alpha", "Before the last brief");
        work.close(s3.id(), SessionWork.INTEGRATED, "012", "old", "main", null);
        dsl.update(SESSION_WORK).set(SESSION_WORK.CLOSED_AT, since.minusMinutes(5))
                .where(SESSION_WORK.SESSION_ID.eq(s3.id())).execute();
        prompt(sid(), "alpha", "Still open");

        ProjectFacts facts = facts(read(), "alpha");

        assertThat(facts.integrated()).isEqualTo(1);
        assertThat(facts.verified()).isEqualTo(1);
        // The latest first.
        assertThat(facts.closed()).hasSize(2);
        assertThat(facts.closed().get(0).result()).isEqualTo(SessionWork.VERIFIED);
        assertThat(facts.closed().get(0).intent()).isEqualTo("Tidy the docs");
        assertThat(facts.closed().get(1).result()).isEqualTo(SessionWork.INTEGRATED);
        assertThat(facts.closed().get(1).intent()).isEqualTo("Fix the reaper");
        assertThat(OffsetDateTime.parse(facts.closed().get(1).closedAt())).isAfter(since);
    }

    @Test
    void atMostEightClosedPiecesAreListedAndAllAreCounted() {
        active("alpha");
        for (int i = 0; i < 10; i++) {
            Session s = prompt(sid(), "alpha", "Piece " + i);
            work.close(s.id(), i < 7 ? SessionWork.INTEGRATED : SessionWork.VERIFIED, "abc", "b", "main", null);
        }

        ProjectFacts facts = facts(read(), "alpha");

        assertThat(facts.closed()).hasSize(BriefSnapshot.MAX_CLOSED);
        assertThat(facts.integrated()).isEqualTo(7);
        assertThat(facts.verified()).isEqualTo(3);
    }

    // ─────────────────────────── waiting on the user ───────────────────────────

    @Test
    void openItemsAreListedMostUrgentFirstAndTheRestElsewhere() {
        Project p = active("alpha");
        Session stranger = prompt(sid(), "unknown", "What is this repo?");
        String key = "brief-it:" + UUID.randomUUID() + ":";
        attention.raise("session_idle", key + 1, AttentionItem.NORMAL, p.id(), null, "A  normal\none", "");
        attention.raise("session_question", key + 2, AttentionItem.URGENT, p.id(), null, "An urgent one", "");
        attention.raise("session_loop", key + 3, AttentionItem.CRITICAL, p.id(), null, "A critical one", "");
        attention.raise("session_question", key + 4, AttentionItem.NORMAL, p.id(), null, "Another normal one", "");
        AttentionItem seen = attention.raise("session_question", key + 5, AttentionItem.URGENT, p.id(), null,
                "Seen already", "");
        attention.ack(seen.id());
        AttentionItem answered = attention.raise("session_question", key + 6, AttentionItem.URGENT, p.id(), null,
                "Answered", "");
        attention.resolve(answered.id(), "yes", "user");
        attention.raise("evidence_missing", key + 7, AttentionItem.URGENT, null, null, "No project at all", "");
        attention.raise("session_question", key + 8, AttentionItem.NORMAL, stranger.projectId(), null,
                "From the proposed project", "");

        BriefSnapshot s = read();
        ProjectFacts facts = facts(s, "alpha");

        assertThat(facts.waiting()).extracting(Item::title)
                .containsExactly("A critical one", "An urgent one", "A normal one", "Another normal one");
        assertThat(facts.waiting()).extracting(Item::urgency).containsExactly("critical", "urgent", "normal", "normal");
        assertThat(facts.waiting().get(0).kind()).isEqualTo("session_loop");
        assertThat(facts.waiting().get(0).createdAt()).isNotNull();
        assertThat(facts.waitingTotal()).isEqualTo(4);
        assertThat(facts.waitingUrgent()).isEqualTo(2);
        // The urgent one first, then the proposed project's own question and the item filed for it.
        assertThat(s.elsewhere()).extracting(Item::title).startsWith("No project at all")
                .contains("From the proposed project").hasSize(3);
        assertThat(s.elsewhere()).extracting(Item::kind).contains(AttentionItem.PROJECT_PROPOSED);
        assertThat(s.elsewhereTotal()).isEqualTo(3);
    }

    @Test
    void atMostEightItemsPerProjectAndFiveElsewhereAreListedAndAllAreCounted() {
        Project p = active("alpha");
        String key = "brief-it:" + UUID.randomUUID() + ":";
        for (int i = 0; i < 10; i++) {
            attention.raise("session_question", key + "p" + i, i == 9 ? AttentionItem.URGENT : AttentionItem.NORMAL,
                    p.id(), null, "Item " + i + " " + "y".repeat(200), "");
        }
        for (int i = 0; i < 7; i++) {
            attention.raise("evidence_missing", key + "e" + i, AttentionItem.NORMAL, null, null, "Elsewhere " + i, "");
        }

        BriefSnapshot s = read();
        ProjectFacts facts = facts(s, "alpha");

        assertThat(facts.waiting()).hasSize(BriefSnapshot.MAX_WAITING);
        assertThat(facts.waiting().get(0).title()).startsWith("Item 9 yyy").hasSize(BriefSnapshot.TITLE);
        assertThat(facts.waitingTotal()).isEqualTo(10);
        assertThat(facts.waitingUrgent()).isEqualTo(1);
        assertThat(s.elsewhere()).hasSize(BriefSnapshot.MAX_ELSEWHERE);
        assertThat(s.elsewhereTotal()).isEqualTo(7);
    }

    // ─────────────────────────── focus and rhythm ───────────────────────────

    @Test
    void promptsSinceTheLastBriefAreCountedByProject() {
        active("alpha");
        active("beta");
        Session a = prompt(sid(), "alpha", "One");
        prompt(a.externalId(), "alpha", "Two");
        prompt(a.externalId(), "alpha", "Before the last brief");
        received(eventIds(a).get(2), since.minusMinutes(5));
        stop(a.externalId(), "alpha", "Done.");
        prompt(sid(), "unknown", "Outside the projects");
        Session b = prompt(sid(), "beta", "Three");

        BriefSnapshot s = read();

        // beta was heard from last.
        assertThat(s.projectNames()).containsExactly("beta", "alpha");
        assertThat(facts(s, "alpha").prompts()).isEqualTo(2);
        assertThat(facts(s, "beta").prompts()).isEqualTo(1);
        assertThat(s.promptsTotal()).isEqualTo(4);
        assertThat(OffsetDateTime.parse(facts(s, "beta").lastPromptAt())).isAfter(since);
        assertThat(OffsetDateTime.parse(facts(s, "beta").lastPromptAt()).toInstant())
                .isCloseTo(b.lastEventAt().toInstant(), within(5, ChronoUnit.SECONDS));
        assertThat(facts(s, "alpha").lastPromptAt()).isNotNull();
    }

    @Test
    void aProjectWithNoPromptHasNoFocus() {
        active("alpha");
        stop(sid(), "alpha", "Done.");

        ProjectFacts facts = facts(read(), "alpha");

        assertThat(facts.prompts()).isZero();
        assertThat(facts.lastPromptAt()).isNull();
        assertThat(facts.sessions()).extracting(SessionFacts::state).containsExactly(BriefSnapshot.STOPPED);
    }

    @Test
    void theRhythmIsReadFromTheDaysWithAPromptOrAStop() {
        Project p = active("alpha");
        OffsetDateTime noon = now.toLocalDate().atTime(12, 0).atOffset(ZoneOffset.UTC);
        Session s1 = prompt(sid(), "alpha", "One");
        stop(s1.externalId(), "alpha", "Done.");
        prompt(s1.externalId(), "alpha", "Two");
        stop(s1.externalId(), "alpha", "Done.");
        prompt(s1.externalId(), "alpha", "Three");
        event(s1.externalId(), NeutralEvent.ACTIVE, repo("alpha"), null, null);
        List<Long> ids = eventIds(s1);
        assertThat(ids).hasSize(6);
        // Four distinct days (two events share one), one event too old to count, and one that is no prompt or stop.
        received(ids.get(0), noon.minusDays(5));
        received(ids.get(1), noon.minusDays(3));
        received(ids.get(2), noon.minusDays(3).plusHours(2));
        received(ids.get(3), noon.minusDays(2));
        received(ids.get(4), noon.minusDays(1));
        received(ids.get(5), noon.minusDays(9));
        Session ancient = prompt(sid(), "alpha", "Long ago");
        received(eventIds(ancient).get(0), noon.minusDays(BriefSnapshot.HISTORY_DAYS + 5));
        ZonedDateTime at = noon.toZonedDateTime().plusHours(1);

        Quiet quiet = facts(snapshots.read(since, at), "alpha").quiet();

        assertThat(quiet.activeDays()).isEqualTo(4);
        assertThat(quiet.lastActiveDay()).isEqualTo(now.toLocalDate().minusDays(1).toString());
        assertThat(quiet.daysSince()).isEqualTo(1);
        assertThat(quiet.usualGap()).isEqualTo(1.0);
        assertThat(quiet.longestGap()).isEqualTo(2);
        assertThat(quiet.cadenceDays()).isNull();
        assertThat(quiet.verdict()).isEqualTo(Quiet.NORMAL);

        // Read three days later, the same history is quiet: 4 silent days against a longest gap of 2.
        assertThat(facts(snapshots.read(since, at.plusDays(3)), "alpha").quiet().verdict()).isEqualTo(Quiet.QUIET);

        // With a cadence the project says itself how often it is worked on.
        space.setConvention(p.id(), null, Quiet.CADENCE, TextNode.valueOf("weekly"), Author.USER);
        Quiet weekly = facts(snapshots.read(since, at.plusDays(3)), "alpha").quiet();
        assertThat(weekly.cadenceDays()).isEqualTo(7);
        assertThat(weekly.daysSince()).isEqualTo(4);
        assertThat(weekly.verdict()).isEqualTo(Quiet.NORMAL);
        assertThat(facts(snapshots.read(since, at.plusDays(7)), "alpha").quiet().verdict()).isEqualTo(Quiet.QUIET);
    }

    @Test
    void anActiveDayIsADayInTheUsersZone() {
        active("alpha");
        Session s1 = prompt(sid(), "alpha", "Late at night");
        // 23:30 UTC two days ago: already the next day east of Greenwich.
        LocalDate day = now.toLocalDate().minusDays(2);
        OffsetDateTime late = day.atTime(23, 30).atOffset(ZoneOffset.UTC);
        received(eventIds(s1).get(0), late);
        Instant at = now.toLocalDate().atTime(12, 0).toInstant(ZoneOffset.UTC);

        for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneId.of("UTC"), ZoneOffset.ofHours(-5),
                ZoneId.of("America/New_York"))) {
            BriefSnapshot s = snapshots.read(since, ZonedDateTime.ofInstant(at, zone));
            assertThat(s.zone()).isEqualTo(zone.getId());
            assertThat(facts(s, "alpha").quiet().lastActiveDay()).as(zone.getId()).isEqualTo(day.toString());
        }
        for (ZoneId zone : List.of(ZoneOffset.ofHours(2), ZoneId.of("GMT+02:00"), ZoneId.of("Europe/Berlin"),
                ZoneId.of("Asia/Tokyo"))) {
            BriefSnapshot s = snapshots.read(since, ZonedDateTime.ofInstant(at, zone));
            assertThat(facts(s, "alpha").quiet().lastActiveDay()).as(zone.getId())
                    .isEqualTo(day.plusDays(1).toString());
            assertThat(facts(s, "alpha").quiet().activeDays()).isEqualTo(1);
        }
    }

    // ─────────────────────────── orange's own tasks ───────────────────────────

    @Test
    void orangesTasksAreCountedOutsideTheProjects() {
        List<Task> created = tasks.createGraph(List.of(
                new TaskDef("a", "Rotate  the\nkey", null, null, null, null),
                new TaskDef("b", "Second in line " + "z".repeat(200), null, null, null, null),
                new TaskDef("c", "Ready one", null, null, null, null),
                new TaskDef("d", "Ready two", null, null, null, null),
                new TaskDef("e", "Done before", null, null, null, null),
                new TaskDef("f", "Done since", null, null, null, null),
                new TaskDef("g", "Given up", null, null, null, null)), List.of());
        status(created.get(0), "needs_human");
        status(created.get(1), "needs_human");
        status(created.get(4), "integrated");
        status(created.get(5), "integrated");
        status(created.get(6), "cancelled");
        long before = taskEvents.append(created.get(4).id(), "orange", "integrated", null);
        dsl.update(TASK_EVENTS).set(TASK_EVENTS.CREATED_AT, since.minusMinutes(5)).where(TASK_EVENTS.ID.eq(before))
                .execute();
        taskEvents.append(created.get(5).id(), "orange", "integrated", null);
        taskEvents.append(created.get(5).id(), "orange", "worktree_disposed", null);

        BriefSnapshot s = read();

        assertThat(s.tasks().byStatus()).containsExactly(entry("ready", 2), entry("needs_human", 2));
        assertThat(s.tasks().integratedSince()).isEqualTo(1);
        assertThat(s.tasks().needsHuman()).hasSize(2);
        assertThat(s.tasks().needsHuman().get(0)).isEqualTo("Rotate the key");
        assertThat(s.tasks().needsHuman().get(1)).startsWith("Second in line zzz").hasSize(BriefSnapshot.TITLE);
        assertThat(s.projects()).isEmpty();
    }

    void status(Task task, String status) {
        dsl.update(TASKS).set(TASKS.STATUS, status).where(TASKS.ID.eq(task.id())).execute();
    }

    @Test
    void theSnapshotOfAFullDatabaseIsStoredAndReadBack() {
        Project p = active("alpha");
        space.add(p.id(), SpaceItem.GOAL, "Ship it", Author.USER, null, null, null);
        space.add(p.id(), SpaceItem.MILESTONE, "Beta", Author.USER, now.toOffsetDateTime().plusDays(3), null, null);
        Session s1 = prompt(sid(), "alpha", "Build the brief");
        stop(s1.externalId(), "alpha", "Shall I continue?");
        tasks.createGraph(List.of(new TaskDef("a", "A task", null, null, null, null)), List.of());

        BriefSnapshot s = read();

        assertThat(BriefSnapshot.parse(s.toJsonb())).isEqualTo(s);
        assertThat(s.hasNews(null)).isTrue();
        assertThat(s.hasNews(s)).as("a session asks: an item is open").isTrue();
        assertThat(facts(s, "alpha").waiting()).extracting(Item::kind).containsExactly(AttentionItem.SESSION_QUESTION);
    }
}
