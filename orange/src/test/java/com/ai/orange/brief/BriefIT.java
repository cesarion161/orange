package com.ai.orange.brief;

import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;
import static com.ai.orange.db.jooq.Tables.BRIEFS;
import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefService.Focus;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.McpTestClient;
import com.ai.orange.notify.AttentionNotifier;
import com.ai.orange.notify.NotifyProperties;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.SpaceService.Author;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.WorkerRunner;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A brief from its brief time to the phone, with a fake {@code claude} and a fake ntfy: the facts
 * are read, a brief run adds the words, and whatever the run does (fails, says nothing, names a
 * project that is not there, is not started at all) the brief is still written from the facts.
 * Then it is pushed once, and {@code orange_focus} returns it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"orange.brief.first-window=72h", "orange.notify.ntfy.brief-topic=my-brief",
                "orange.brief.public-url=https://orange.example"})
@Import(TestcontainersConfiguration.class)
class BriefIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("brief-it-");
    static final Pattern PROJECT_LINE = Pattern.compile("(?m)^## Project: (.+)$");

    /** What the fake ntfy took, and the status it answers with. */
    static final List<JsonNode> PUBLISHED = new CopyOnWriteArrayList<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final HttpServer NTFY = startNtfy();

    static HttpServer startNtfy() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (STATUS.get() == 200) PUBLISHED.add(Json.mapper().readTree(body));
                exchange.sendResponseHeaders(STATUS.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        FakeClaude.register(r, RUN_ROOT);
        r.add("orange.notify.ntfy.url", () -> "http://127.0.0.1:" + NTFY.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        NTFY.stop(0);
    }

    @Value("${local.server.port}") int port;
    @Autowired BriefDelivery delivery;
    @Autowired AttentionNotifier attentionNotifier;

    @Autowired BriefScheduler scheduler;
    @Autowired BriefService service;
    @Autowired BriefRepository briefs;
    @Autowired ProjectService projects;
    @Autowired SessionService sessions;
    @Autowired SpaceService space;
    @Autowired AttentionService attention;
    @Autowired AttentionRepository attentionItems;
    @Autowired RunRepository runs;
    @Autowired RunsProperties runProps;
    @Autowired SchedulerPause pause;
    @Autowired NotifyProperties notify;
    @Autowired BriefProperties briefProps;
    @Autowired WorkerRunner runner;
    @Autowired DSLContext dsl;

    /** Tomorrow at 09:05 in the user's zone: what the test creates lies before it, and the 09:00 brief is due. */
    ZonedDateTime now;
    /** The same day at 14:05, when the second brief is due. */
    ZonedDateTime afternoon;
    final Map<UUID, String> repos = new HashMap<>();

    @BeforeEach
    void fresh() {
        clean();
        PUBLISHED.clear();
        STATUS.set(200);
        now = ZonedDateTime.now(notify.zoneId()).plusDays(1).withHour(9).withMinute(5).truncatedTo(ChronoUnit.MINUTES);
        afternoon = now.withHour(14);
    }

    /** Back to a database with no brief, no brief run, nothing waiting and no project. */
    void clean() {
        pause.resume();
        dsl.deleteFrom(BRIEFS).execute();
        dsl.deleteFrom(RUNS).where(RUNS.KIND.eq(RunKind.BRIEF.dbValue())).execute();
        dsl.deleteFrom(ATTENTION_ITEMS).execute();
        dsl.deleteFrom(SESSIONS).execute();
        dsl.deleteFrom(PROJECTS).execute();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** An active project with a repo; a fake marker goes into its summary, which the prompt carries. */
    Project project(String name, String summary) {
        Project p = projects.create(name, summary);
        String repo = "/tmp/brief-" + UUID.randomUUID().toString().substring(0, 8);
        projects.addRepo(p.id(), "local", repo);
        repos.put(p.id(), repo);
        return p;
    }

    /** A session in the project's repo with one prompt from the user. */
    Session prompt(Project p, String text) {
        String repo = repos.get(p.id());
        ObjectNode e = Json.mapper().createObjectNode().put("event_id", UUID.randomUUID().toString())
                .put("harness", "claude-code").put("session_id", "brief-" + UUID.randomUUID())
                .put("type", NeutralEvent.USER_PROMPT).put("cwd", repo).put("repo_root", repo).put("branch", "main");
        e.putObject("payload").put("prompt", text);
        return sessions.apply(NeutralEvent.parse(e));
    }

    /** An active project with one session, and the fake scenario its brief run plays (null = a plain brief). */
    Project working(String name, String marker) {
        Project p = project(name, marker == null ? "A project" : "A project [[fake:" + marker + "]]");
        prompt(p, "Add retry with backoff");
        return p;
    }

    List<Run> briefRuns() {
        return dsl.select(RUNS.ID).from(RUNS).where(RUNS.KIND.eq(RunKind.BRIEF.dbValue()))
                .orderBy(RUNS.QUEUED_AT.asc(), RUNS.ID.asc()).fetch(RUNS.ID).stream()
                .map(id -> runs.findById(id).orElseThrow()).toList();
    }

    List<Brief> allBriefs() {
        return dsl.selectFrom(BRIEFS).orderBy(BRIEFS.SNAPSHOT_AT.asc(), BRIEFS.ID.asc()).fetch()
                .map(BriefRepository::toDomain);
    }

    /** The one brief there is. */
    Brief brief() {
        assertThat(allBriefs()).hasSize(1);
        return allBriefs().getFirst();
    }

    static String runFile(UUID runId, String name) throws IOException {
        return Files.readString(RUN_ROOT.resolve(runId.toString()).resolve("claude-config").resolve(name));
    }

    static BriefContent content(Brief b) {
        return BriefContent.read(b.content()).orElseThrow();
    }

    static ProjectFacts facts(Brief b, String name) {
        return BriefSnapshot.parse(b.snapshot()).projects().stream().filter(p -> name.equals(p.name()))
                .findFirst().orElseThrow();
    }

    /**
     * A scheduler as the application runs it, with the two switches the test configuration turns
     * off ({@code orange.brief.enabled}, {@code orange.notify.enabled}) set by the test.
     */
    BriefScheduler sweeper(boolean briefs, boolean pushes) {
        BriefProperties b = new BriefProperties(briefs, briefProps.sweep(), briefProps.catchUp(),
                briefProps.firstWindow(), briefProps.freshMinInterval(), briefProps.focusWait(), briefProps.publicUrl(),
                briefProps.linkTtl(), briefProps.retention());
        NotifyProperties n = new NotifyProperties(pushes, notify.sweep(), notify.batchWindow(), notify.zone(),
                notify.ntfy());
        return new BriefScheduler(service, delivery, this.briefs, b, n);
    }

    /** Waits for a sweep's tick to end. */
    static void finish(Thread tick) throws InterruptedException {
        assertThat(tick).as("the sweep started a tick").isNotNull();
        assertThat(tick.join(Duration.ofSeconds(120))).as("the tick ended").isTrue();
    }

    List<AttentionItem> urgentItems() {
        return attentionItems.find(null, 50).stream().filter(i -> AttentionItem.BRIEF_URGENT.equals(i.kind())).toList();
    }

    // ─────────────────────────── a brief time ───────────────────────────

    @Test
    void aBriefTimeWritesOneBrief() throws Exception {
        Project alpha = working("alpha", null);
        space.add(alpha.id(), SpaceItem.GOAL, "Ship the beta to ten users", Author.USER, null, null, null);
        ZonedDateTime slot = now.withMinute(0);

        scheduler.tick(now);

        assertThat(briefRuns()).hasSize(1);
        Run run = briefRuns().getFirst();
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.taskId()).isNull();
        assertThat(run.attendedSessionId()).isNull();
        assertThat(run.role()).isEqualTo("brief");
        assertThat(run.tier()).as("brief runs route medium and low").isEqualTo("medium");
        assertThat(run.effort()).isEqualTo("low");
        assertThat(run.input().data()).contains("\"scheduled\"").contains(BriefService.slotKey(slot)).contains("\"alpha\"");
        assertThat(runs.existsForSlot(RunKind.BRIEF, BriefService.slotKey(slot))).isTrue();

        Brief b = brief();
        assertThat(b.slotAt().toInstant()).isEqualTo(slot.toInstant());
        assertThat(b.snapshotAt().toInstant()).isEqualTo(now.toInstant());
        assertThat(b.runId()).isEqualTo(run.id());
        assertThat(b.note()).isNull();
        assertThat(b.pushable()).as("the first brief always has something new").isTrue();
        assertThat(b.deliveredAt()).as("the same tick pushed it").isNotNull();
        assertThat(b.pageKey()).isNotBlank();
        assertThat(content(b).projects()).singleElement().satisfies(line -> {
            assertThat(line.project()).isEqualTo("alpha");
            assertThat(line.statusLine()).isEqualTo("alpha: steady, nothing blocked.");
        });
        assertThat(content(b).focus()).singleElement()
                .satisfies(f -> assertThat(f.item()).isEqualTo("Answer the open question"));
        assertThat(content(b).urgent()).isEmpty();
        assertThat(facts(b, "alpha").prompts()).as("the facts are stored beside the lines").isEqualTo(1);
        assertThat(BriefText.title(b)).doesNotContain("facts only");
        assertThat(BriefText.text(b)).contains("alpha — alpha: steady, nothing blocked.");

        String prompt = runFile(run.id(), "fake-prompt.txt");
        assertThat(prompt).startsWith("# Write the focus brief").contains("## Project: alpha")
                .contains("Ship the beta to ten users");
        String args = runFile(run.id(), "fake-args.json");
        assertThat(args).contains("mcp__orange__submit_brief");
        assertThat(args).as("the brief gets no built-in tools").contains("\"--tools\",\"\"")
                .doesNotContain("\"Read\"").doesNotContain("\"Bash\"");
        assertThat(args).doesNotContain(repos.get(alpha.id()));
        try (Stream<Path> left = Files.list(RUN_ROOT.resolve("brief"))) {
            assertThat(left).as("the run's empty directory is removed").isEmpty();
        }
        assertThat(pause.current()).isEmpty();
        assertThat(urgentItems()).isEmpty();

        // The slot is taken: another tick, or a restart, writes nothing and runs nothing.
        scheduler.tick(now);
        scheduler.tick(now.plusMinutes(30));
        assertThat(briefRuns()).hasSize(1);
        assertThat(allBriefs()).hasSize(1);
        assertThat(service.scheduled(now)).isEmpty();
        assertThat(service.write(slot, now).why()).isEqualTo(BriefService.Written.TAKEN);
    }

    @Test
    void aRunThatFailsGivesTheFacts() {
        working("alpha", "error");
        scheduler.tick(now);

        Brief failed = brief();
        assertThat(failed.content()).isNull();
        assertThat(failed.note()).isEqualTo("no model lines (error_result): facts only");
        assertThat(failed.runId()).isEqualTo(briefRuns().getFirst().id());
        assertThat(briefRuns().getFirst().status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.pushable()).as("the facts still go to the phone").isTrue();
        assertThat(pause.current()).as("a model failure does not pause").isEmpty();
        assertThat(BriefText.title(failed)).endsWith("· facts only");
        assertThat(BriefText.text(failed)).contains("no model lines (error_result)")
                .contains("alpha — " + BriefText.ruleLine(facts(failed, "alpha")));
        assertThat(BriefText.ruleLine(facts(failed, "alpha"))).contains("1 session").contains("1 prompt");

        clean();
        working("beta", "no-outcome");
        scheduler.tick(now);

        Brief silent = brief();
        assertThat(silent.content()).isNull();
        assertThat(silent.note()).contains("no_outcome");
        assertThat(briefRuns()).singleElement().satisfies(r -> {
            assertThat(r.status()).as("a brief run that submits nothing has failed").isEqualTo(RunStatus.FAILED);
            assertThat(r.error()).isEqualTo("no_outcome");
        });
        assertThat(pause.current()).isEmpty();
        assertThat(BriefText.text(silent)).contains("beta — " + BriefText.ruleLine(facts(silent, "beta")));
    }

    @Test
    void aRefusedCallRecordsNothing() throws Exception {
        working("alpha", "brief-unknown-project");
        scheduler.tick(now);

        Brief refused = brief();
        assertThat(refused.content()).isNull();
        assertThat(refused.note()).contains("no_outcome");
        Run run = briefRuns().getFirst();
        assertThat(run.outcome()).isNull();
        assertThat(runFile(run.id(), "fake-mcp.log")).as("the refusal names the projects there are")
                .contains("no-such-project").contains("alpha");

        clean();
        working("beta", "brief-corrected");
        scheduler.tick(now);

        Brief corrected = brief();
        assertThat(corrected.note()).isNull();
        assertThat(content(corrected).projects()).singleElement()
                .satisfies(line -> assertThat(line.project()).isEqualTo("beta"));
        assertThat(briefRuns()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED));
    }

    // ─────────────────────────── no run ───────────────────────────

    @Test
    void aPausedSchedulerGetsTheFactsAndNoRun() {
        working("alpha", null);
        pause.pause("plan_limit", OffsetDateTime.now().plusHours(1));

        scheduler.tick(now);

        assertThat(briefRuns()).isEmpty();
        Brief b = brief();
        assertThat(b.content()).isNull();
        assertThat(b.runId()).isNull();
        assertThat(b.note()).startsWith("orange is paused (plan_limit until ").endsWith("): facts only");
        assertThat(b.pushable()).isTrue();
        assertThat(pause.current()).as("writing a brief does not resume anything").isPresent();
    }

    @Test
    void aPlanLimitPausesAndStillBriefs() {
        working("alpha", "plan-limit");

        scheduler.tick(now);

        Brief b = brief();
        assertThat(b.content()).isNull();
        assertThat(b.note()).isEqualTo("no model lines (plan_limit): facts only");
        assertThat(pause.current()).isPresent().get().satisfies(p -> assertThat(p.reason()).isEqualTo("plan_limit"));
    }

    @Test
    void aBriefTimeIsTriedOnce() {
        working("alpha", null);
        // A brief run for this time that orange was stopped under: it left a run and no brief.
        String key = BriefService.slotKey(now.withMinute(0));
        Run cut = runs.insertRunning(RunKind.BRIEF, "brief", null, runProps.workerId(), runProps.leaseTtl(),
                JSONB.valueOf("{\"trigger\":\"scheduled\",\"slot\":\"" + key + "\",\"projects\":[\"alpha\"]}"));
        assertThat(runs.finish(cut.id(), RunStatus.FAILED, "coordinator_restart", BigDecimal.ZERO, 0, 0, 0, null)).isTrue();

        scheduler.tick(now);

        assertThat(briefRuns()).as("the model is not asked a second time").singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(cut.id()));
        Brief b = brief();
        assertThat(b.content()).isNull();
        assertThat(b.note()).isEqualTo("the brief run for this time did not finish: facts only");
        assertThat(b.pushable()).isTrue();
    }

    @Test
    @DirtiesContext
    void orangeStoppingDuringABriefRunWritesNothingNow() throws Exception {
        working("alpha", "hang");
        ZonedDateTime slot = now.withMinute(0);
        CompletableFuture<BriefService.Written> writing = CompletableFuture.supplyAsync(() -> service.write(slot, now));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (runner.activeRunIds().isEmpty()) {
            assertThat(System.nanoTime()).as("the brief run never started").isLessThan(deadline);
            Thread.sleep(100);
        }

        runner.shutdown();

        assertThat(writing.get(30, TimeUnit.SECONDS).why()).isEqualTo(BriefService.Written.STOPPING);
        assertThat(allBriefs()).as("no brief from the facts while orange stops: the next start writes it").isEmpty();
        assertThat(briefRuns()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(RunStatus.FAILED);
            assertThat(r.error()).isEqualTo("coordinator_restart");
        });
        assertThat(pause.current()).isEmpty();

        // What the next start finds: the run and no brief. The facts, and the model is not asked again.
        scheduler.tick(now.plusMinutes(1));
        assertThat(brief().note()).isEqualTo(BriefService.RUN_DID_NOT_FINISH);
        assertThat(brief().slotAt().toInstant()).isEqualTo(slot.toInstant());
        assertThat(briefRuns()).hasSize(1);
    }

    // ─────────────────────────── the sweep ───────────────────────────

    @Test
    void aSweepRunsOneTickAtATimeAndReadsTheTimeAtEachStep() throws Exception {
        working("alpha", null);
        BriefScheduler sweeper = sweeper(true, true);
        // The clock stands at 09:05 until the test lets it go; by the time the brief is written it is 09:09.
        CountDownLatch looked = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger looks = new AtomicInteger();
        Supplier<ZonedDateTime> clock = () -> {
            if (looks.getAndIncrement() > 0) return now.plusMinutes(4);
            looked.countDown();
            try {
                go.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return now;
        };

        Thread tick = sweeper.sweep(clock);
        assertThat(tick).isNotNull();
        assertThat(tick.getName()).as("on a thread of its own, not the scheduler's").isEqualTo("brief-tick");
        assertThat(looked.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(sweeper.sweep(clock)).as("a tick is running: this sweep does nothing").isNull();
        go.countDown();
        finish(tick);

        Brief b = brief();
        assertThat(b.snapshotAt().toInstant()).isEqualTo(now.toInstant());
        assertThat(briefRuns()).hasSize(1);
        assertThat(PUBLISHED).hasSize(1);
        assertThat(b.deliveredAt().toInstant()).as("pushed at the time after the brief run, not the one before it")
                .isEqualTo(now.plusMinutes(4).toInstant());
        assertThat(looks.get()).as("one look per step, none by the sweep that did nothing").isEqualTo(3);

        // The tick is over, so the next sweep runs; the brief time has its brief.
        finish(sweeper.sweep(() -> now.plusMinutes(5)));
        assertThat(allBriefs()).hasSize(1);
        assertThat(PUBLISHED).hasSize(1);
    }

    @Test
    void switchedOffTheSweepOnlyDeletesOldBriefs() throws Exception {
        working("alpha", null);
        // orange_focus still writes briefs with the switch off, session text included: they must not stay for ever.
        Brief asked = service.focus(true, now.minusMinutes(30)).brief();
        dsl.update(BRIEFS).set(BRIEFS.CREATED_AT, now.minusDays(31).toOffsetDateTime()).where(BRIEFS.ID.eq(asked.id()))
                .execute();

        finish(sweeper(false, true).sweep(() -> now));

        assertThat(allBriefs()).as("the old brief is gone, and none was written for 09:00").isEmpty();
        assertThat(briefRuns()).as("only the run of the brief that was asked for").hasSize(1);
        assertThat(PUBLISHED).isEmpty();
    }

    @Test
    void withPushesSwitchedOffABriefIsWrittenAndStaysOnItsPage() throws Exception {
        working("alpha", null);

        finish(sweeper(true, false).sweep(() -> now));

        Brief b = brief();
        assertThat(b.pushable()).isTrue();
        assertThat(b.deliveredAt()).isNull();
        assertThat(PUBLISHED).as("orange.notify.enabled is the switch for every push").isEmpty();

        finish(sweeper(true, true).sweep(() -> now.plusMinutes(1)));
        assertThat(PUBLISHED).hasSize(1);
        assertThat(brief().deliveredAt().toInstant()).isEqualTo(now.plusMinutes(1).toInstant());
    }

    @Test
    void nothingNewIsWrittenButNotPushable() {
        working("alpha", null);
        scheduler.tick(now);
        Brief morning = brief();

        scheduler.tick(afternoon);

        assertThat(briefRuns()).as("nothing new: the model is not asked").hasSize(1);
        assertThat(allBriefs()).hasSize(2);
        Brief later = allBriefs().getLast();
        assertThat(later.slotAt().toInstant()).isEqualTo(afternoon.withMinute(0).toInstant());
        assertThat(later.note()).isEqualTo("nothing new since the last brief");
        assertThat(later.content()).isNull();
        assertThat(later.runId()).isNull();
        assertThat(later.pushable()).isFalse();
        assertThat(BriefSnapshot.parse(later.snapshot()).since()).as("it covers what happened since the brief before")
                .satisfies(since -> assertThat(OffsetDateTime.parse(since).toInstant())
                        .isEqualTo(morning.snapshotAt().toInstant()));
        assertThat(facts(later, "alpha").prompts()).isZero();
    }

    @Test
    void onlyActiveProjectsAreBriefed() throws Exception {
        Project held = project("held-back", null);
        projects.update(held.id(), null, Project.PAUSED, null);
        Project old = project("long-gone", null);
        projects.update(old.id(), null, Project.ARCHIVED, null);

        scheduler.tick(now);
        assertThat(allBriefs()).as("no active project: no brief").isEmpty();
        assertThat(briefRuns()).isEmpty();
        assertThat(service.write(now.withMinute(0), now).why()).isEqualTo(BriefService.Written.NO_PROJECT);

        working("alpha", null);
        scheduler.tick(now);

        Brief b = brief();
        assertThat(content(b).projects()).extracting(BriefContent.Line::project).containsExactly("alpha");
        assertThat(BriefSnapshot.parse(b.snapshot()).projectNames()).containsExactly("alpha");
        String prompt = runFile(b.runId(), "fake-prompt.txt");
        assertThat(prompt).contains("## Project: alpha").doesNotContain("held-back").doesNotContain("long-gone");
        assertThat(briefRuns().getFirst().input().data()).doesNotContain("held-back").doesNotContain("long-gone");
    }

    // ─────────────────────────── what the model may not do ───────────────────────────

    @Test
    void anUrgentEntryIsFiledOnceAndStaysNormal() {
        Project alpha = working("alpha", "brief-urgent");

        scheduler.tick(now);

        Brief b = brief();
        assertThat(content(b).urgent()).hasSize(1);
        assertThat(urgentItems()).singleElement().satisfies(i -> {
            assertThat(i.urgency()).isEqualTo(AttentionItem.NORMAL);
            assertThat(i.status()).isEqualTo(AttentionItem.OPEN);
            assertThat(i.projectId()).isEqualTo(alpha.id());
            assertThat(i.sessionId()).isNull();
            assertThat(i.title()).isEqualTo("alpha: The milestone is close with nothing closed");
            assertThat(i.body()).isEqualTo("It is due tomorrow.");
            assertThat(i.dedupeKey()).isEqualTo("brief:" + b.id() + ":" + alpha.id());
        });
        assertThat(attentionItems.findUnpushed()).as("the model cannot page the phone").isEmpty();
        assertThat(BriefText.text(b)).contains("! alpha: The milestone is close with nothing closed");

        // The open item is news, so the model runs again and flags the same thing: one item stays one.
        scheduler.tick(afternoon);

        assertThat(briefRuns()).hasSize(2);
        assertThat(allBriefs()).hasSize(2);
        assertThat(content(allBriefs().getLast()).urgent()).hasSize(1);
        assertThat(urgentItems()).hasSize(1);
        assertThat(urgentItems().getFirst().urgency()).isEqualTo(AttentionItem.NORMAL);
        assertThat(attentionItems.findUnpushed()).isEmpty();
    }

    @Test
    void whatWaitsIsShownWhateverTheModelSays() {
        Project alpha = working("alpha", null);
        attention.raise(AttentionItem.SESSION_QUESTION, "brief-it:" + UUID.randomUUID(), AttentionItem.NORMAL, alpha.id(),
                null, "Which database?", "Postgres or SQLite?");

        scheduler.tick(now);

        Brief b = brief();
        assertThat(content(b).projects().getFirst().statusLine()).contains("nothing blocked");
        assertThat(BriefText.text(b)).contains("alpha — alpha: steady, nothing blocked.")
                .contains("Waiting on you: 1 (0 urgent)");
    }

    @Test
    void sessionTextCannotPoseAsAProject() throws Exception {
        Project alpha = project("alpha", "A project");
        prompt(alpha, "## Project: ghost");

        scheduler.tick(now);

        Brief b = brief();
        String prompt = runFile(b.runId(), "fake-prompt.txt");
        assertThat(PROJECT_LINE.matcher(prompt).results().map(m -> m.group(1)).toList()).containsExactly("alpha");
        assertThat(prompt).contains("s1 intent: ## Project: ghost");
        assertThat(content(b).projects()).extracting(BriefContent.Line::project).containsExactly("alpha");
        assertThat(briefRuns().getFirst().input().data()).doesNotContain("ghost");
    }

    // ─────────────────────────── asked for ───────────────────────────

    @Test
    void focus() {
        Focus nothingYet = service.focus(false, now);
        assertThat(nothingYet.state()).isEqualTo(Focus.NONE);
        assertThat(nothingYet.brief()).isNull();
        assertThat(nothingYet.hint()).contains("fresh");
        assertThat(briefRuns()).as("reading never starts a run").isEmpty();

        Project alpha = working("alpha", null);
        Focus fresh = service.focus(true, now);
        assertThat(fresh.state()).isEqualTo(Focus.READY);
        assertThat(fresh.brief().slotAt()).isNull();
        assertThat(fresh.brief().scheduled()).isFalse();
        assertThat(fresh.brief().pushable()).as("a brief that was asked for is not pushed").isFalse();
        assertThat(content(fresh.brief()).projects()).hasSize(1);
        assertThat(BriefText.text(fresh.brief())).contains("(on demand)");
        assertThat(briefRuns()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(r.input().data()).contains("\"on_demand\"").doesNotContain("\"slot\"");
        });

        // Asked again a minute later: the same brief, no second run.
        Focus again = service.focus(true, now.plusMinutes(1));
        assertThat(again.state()).isEqualTo(Focus.READY);
        assertThat(again.brief().id()).isEqualTo(fresh.brief().id());
        assertThat(again.hint()).contains("1 min ago");
        assertThat(briefRuns()).hasSize(1);
        assertThat(service.focus(false, now.plusMinutes(1)).brief().id()).isEqualTo(fresh.brief().id());

        // Paused: the facts at once, and no run.
        dsl.deleteFrom(BRIEFS).execute();
        pause.pause("auth_failed", null);
        Focus paused = service.focus(true, now);
        assertThat(paused.state()).isEqualTo(Focus.READY);
        assertThat(paused.brief().content()).isNull();
        assertThat(paused.brief().note()).isEqualTo("orange is paused (auth_failed): facts only");
        assertThat(paused.brief().pushable()).isFalse();
        assertThat(briefRuns()).hasSize(1);

        // No active project: nothing to write about.
        pause.resume();
        dsl.deleteFrom(BRIEFS).execute();
        projects.update(alpha.id(), null, Project.ARCHIVED, null);
        Focus none = service.focus(true, now);
        assertThat(none.state()).isEqualTo(Focus.NONE);
        assertThat(none.brief()).isNull();
        assertThat(none.hint()).contains("no active project");
        assertThat(allBriefs()).isEmpty();
        assertThat(briefRuns()).hasSize(1);
    }

    @Test
    void aFreshBriefAskedForRightAfterAnotherIsThatOne() {
        working("alpha", null);
        // Two sessions asked while there was no brief; the second reaches the write as the first one ends.
        Brief first = service.write(null, now).brief();
        BriefService.Written second = service.write(null, now.plusSeconds(5));

        assertThat(second.why()).isNull();
        assertThat(second.brief().id()).isEqualTo(first.id());
        assertThat(allBriefs()).hasSize(1);
        assertThat(briefRuns()).as("no second run inside fresh-min-interval").hasSize(1);

        // Past the interval a fresh brief is a new one again, and a brief time is never held up by it.
        assertThat(service.write(null, now.plusMinutes(11)).brief().id()).isNotEqualTo(first.id());
        assertThat(briefRuns()).hasSize(2);
        assertThat(service.write(now.plusMinutes(12).withMinute(0), now.plusMinutes(12)).brief().scheduled()).isTrue();
        assertThat(allBriefs()).hasSize(3);
    }

    @Test
    void whileABriefIsBeingWrittenASecondWriterIsToldSo() throws Exception {
        working("alpha", null);
        // As if a brief run were under way: the write lock is held (by this thread; writers are others).
        service.lock.lock();
        try {
            Focus asked = service.focus(true, now);
            assertThat(asked.state()).isEqualTo(Focus.WRITING);
            assertThat(asked.brief()).as("there is no brief yet to show meanwhile").isNull();
            assertThat(asked.hint()).contains("being written");

            Thread tick = Thread.ofVirtual().start(() -> scheduler.tick(now));
            tick.join();
            assertThat(allBriefs()).as("nobody waits for the lock, and nobody writes without it").isEmpty();
            assertThat(briefRuns()).isEmpty();
        } finally {
            service.lock.unlock();
        }

        // The next tick finds the brief time still open and writes it.
        scheduler.tick(now.plusMinutes(1));
        assertThat(brief().slotAt().toInstant()).isEqualTo(now.withMinute(0).toInstant());
        assertThat(briefRuns()).hasSize(1);
    }

    @Test
    void aBriefThatWasAskedForDoesNotMoveTheWindow() {
        working("alpha", null);
        Focus asked = service.focus(true, now.minusMinutes(30));
        assertThat(asked.state()).isEqualTo(Focus.READY);
        assertThat(facts(asked.brief(), "alpha").prompts()).isEqualTo(1);

        scheduler.tick(now);

        assertThat(allBriefs()).hasSize(2);
        Brief scheduled = briefs.latestScheduled().orElseThrow();
        assertThat(OffsetDateTime.parse(BriefSnapshot.parse(scheduled.snapshot()).since()).toInstant())
                .as("the scheduled brief still looks back the whole first window")
                .isEqualTo(now.minusHours(72).toInstant());
        assertThat(facts(scheduled, "alpha").prompts()).isEqualTo(1);
        assertThat(scheduled.pushable()).isTrue();
        assertThat(content(scheduled).projects()).hasSize(1);
        assertThat(briefRuns()).hasSize(2);
    }

    // ─────────────────────────── to the phone ───────────────────────────

    @Test
    void aBriefIsPushedOnce() {
        working("alpha", null);

        scheduler.tick(now);

        Brief b = brief();
        assertThat(PUBLISHED).singleElement().satisfies(m -> {
            assertThat(m.path("topic").asText()).isEqualTo("my-brief");
            assertThat(m.path("priority").asInt()).isEqualTo(3);
            assertThat(m.path("title").asText()).startsWith("orange brief").isEqualTo(BriefText.title(b));
            assertThat(m.path("message").asText()).contains("alpha").isEqualTo(BriefText.push(b));
            assertThat(m.path("click").asText()).startsWith("https://orange.example/brief/").contains("?k=")
                    .isEqualTo("https://orange.example/brief/" + b.id() + "?k=" + b.pageKey());
        });
        assertThat(b.deliveredAt().toInstant()).isEqualTo(now.toInstant());

        scheduler.tick(now);
        scheduler.tick(now.plusMinutes(30));
        assertThat(delivery.deliver(now.plusMinutes(31))).isZero();
        assertThat(PUBLISHED).as("a brief goes out once").hasSize(1);
        assertThat(attentionItems.findUnpushed()).isEmpty();
    }

    @Test
    void aFailedPushIsTriedAgainThenDropped() {
        working("alpha", null);
        STATUS.set(500);

        scheduler.tick(now);

        assertThat(brief().deliveredAt()).as("ntfy did not take it").isNull();
        assertThat(PUBLISHED).isEmpty();
        assertThat(delivery.deliver(now.plusSeconds(30))).isZero();
        assertThat(brief().deliveredAt()).isNull();

        STATUS.set(200);
        assertThat(delivery.deliver(now.plusMinutes(1))).isEqualTo(1);
        assertThat(PUBLISHED).hasSize(1);
        assertThat(brief().deliveredAt().toInstant()).isEqualTo(now.plusMinutes(1).toInstant());

        // A brief ntfy has not taken by the end of the catch-up time stays on the page only.
        clean();
        PUBLISHED.clear();
        working("beta", null);
        STATUS.set(500);
        scheduler.tick(now);
        STATUS.set(200);
        assertThat(brief().pushable()).isTrue();
        assertThat(delivery.deliver(now.plusHours(3))).isZero();
        assertThat(PUBLISHED).isEmpty();
        assertThat(brief().deliveredAt()).isNull();
    }

    @Test
    void aNothingNewBriefAndAnOnDemandBriefAreNotPushed() {
        working("alpha", null);
        scheduler.tick(now);
        assertThat(PUBLISHED).hasSize(1);
        PUBLISHED.clear();

        scheduler.tick(afternoon);

        assertThat(allBriefs()).hasSize(2);
        assertThat(allBriefs().getLast().pushable()).isFalse();
        assertThat(PUBLISHED).as("nothing new: nothing on the phone").isEmpty();

        Focus asked = service.focus(true, afternoon.plusMinutes(20));
        assertThat(asked.state()).isEqualTo(Focus.READY);
        assertThat(asked.brief().scheduled()).isFalse();
        assertThat(delivery.deliver(afternoon.plusMinutes(21))).isZero();
        scheduler.tick(afternoon.plusMinutes(22));
        assertThat(PUBLISHED).isEmpty();
        assertThat(allBriefs()).hasSize(3).filteredOn(b -> b.deliveredAt() != null).hasSize(1);
    }

    @Test
    void anUrgentEntryIsNotPushedOnItsOwn() {
        working("alpha", "brief-urgent");

        scheduler.tick(now);

        assertThat(urgentItems()).hasSize(1);
        assertThat(attentionNotifier.flush(now)).as("the model's urgent entry is a normal item").isZero();
        assertThat(attentionNotifier.flush(now.plusMinutes(30))).isZero();
        assertThat(PUBLISHED).singleElement().satisfies(m -> {
            assertThat(m.path("topic").asText()).isEqualTo("my-brief");
            assertThat(m.path("priority").asInt()).as("an urgent entry does not change how the phone rings").isEqualTo(3);
            assertThat(m.path("message").asText()).contains("! alpha: The milestone is close with nothing closed");
        });
        assertThat(urgentItems().getFirst().pushedAt()).isNull();
    }

    @Test
    void oldBriefsAreDeleted() {
        working("alpha", null);
        scheduler.tick(now);
        Brief b = brief();

        scheduler.tick(now.plusMinutes(1));
        assertThat(allBriefs()).as("a young brief stays").hasSize(1);

        dsl.update(BRIEFS).set(BRIEFS.CREATED_AT, now.minusDays(31).toOffsetDateTime()).where(BRIEFS.ID.eq(b.id()))
                .execute();
        scheduler.tick(now.plusMinutes(2));

        assertThat(allBriefs()).isEmpty();
        assertThat(briefRuns()).as("its run stays; only the brief goes").hasSize(1);
    }

    // ─────────────────────────── orange_focus ───────────────────────────

    @Test
    void orangeFocusOverMcp() throws Exception {
        McpTestClient mcp = new McpTestClient("http://localhost:" + port + "/mcp/steering", null);
        mcp.initialize();

        JsonNode nothingYet = focusTool(mcp, false);
        assertThat(nothingYet.path("state").asText()).isEqualTo("none");
        assertThat(nothingYet.path("hint").asText()).contains("fresh");
        assertThat(nothingYet.has("brief")).isFalse();
        assertThat(briefRuns()).isEmpty();

        Project alpha = working("alpha", null);
        JsonNode fresh = focusTool(mcp, true);
        assertThat(fresh.path("state").asText()).isEqualTo("ready");
        JsonNode b = fresh.path("brief");
        assertThat(b.path("model_lines").asBoolean()).isTrue();
        assertThat(b.path("slot").isNull()).as("it was asked for").isTrue();
        assertThat(b.path("note").isNull()).isTrue();
        assertThat(b.path("delivered_at").isNull()).isTrue();
        assertThat(b.path("text").asText()).contains("alpha").contains("Waiting on you").contains("(on demand)");
        Brief stored = brief();
        assertThat(b.path("id").asText()).isEqualTo(stored.id().toString());
        assertThat(b.path("page").asText()).startsWith("https://orange.example/brief/")
                .isEqualTo("https://orange.example/brief/" + stored.id() + "?k=" + stored.pageKey());
        assertThat(OffsetDateTime.parse(b.path("written_at").asText()).toInstant())
                .isEqualTo(stored.snapshotAt().toInstant());
        assertThat(briefRuns()).hasSize(1);
        assertThat(PUBLISHED).as("a brief that was asked for is not pushed").isEmpty();

        JsonNode latest = focusTool(mcp, false);
        assertThat(latest.path("state").asText()).isEqualTo("ready");
        assertThat(latest.path("brief").path("id").asText()).isEqualTo(stored.id().toString());
        // Asked for a fresh one again right away: the same brief, and no second run.
        JsonNode again = focusTool(mcp, true);
        assertThat(again.path("brief").path("id").asText()).isEqualTo(stored.id().toString());
        assertThat(again.path("hint").asText()).contains("at most every");
        assertThat(briefRuns()).hasSize(1);

        dsl.deleteFrom(BRIEFS).execute();
        projects.update(alpha.id(), null, Project.ARCHIVED, null);
        JsonNode none = focusTool(mcp, true);
        assertThat(none.path("state").asText()).isEqualTo("none");
        assertThat(none.path("hint").asText()).contains("no active project");
        assertThat(none.has("brief")).isFalse();
        assertThat(allBriefs()).isEmpty();
        assertThat(briefRuns()).hasSize(1);
    }

    static JsonNode focusTool(McpTestClient mcp, boolean fresh) throws Exception {
        ObjectNode args = Json.mapper().createObjectNode();
        if (fresh) args.put("fresh", true);
        JsonNode result = mcp.callTool("orange_focus", args);
        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        return Json.mapper().readTree(McpTestClient.text(result));
    }
}
