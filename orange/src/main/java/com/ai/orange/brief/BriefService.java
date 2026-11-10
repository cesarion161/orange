package com.ai.orange.brief;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.RunTokens;
import com.ai.orange.notify.NotifyProperties;
import com.ai.orange.notify.QuietHours;
import com.ai.orange.project.Conventions;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.WorkerRunner;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a focus brief (project-space spec §7; Phase D): reads the facts, lets a brief run add
 * the words, and stores both. The facts are the half that is always there: when the model is not
 * asked (nothing new, a paused scheduler, a brief time that already had its run) or its run
 * leaves nothing valid, the brief is written from the facts with a note saying why. The run is
 * started directly and waited for, so there is no queued run to lose.
 */
@Service
public class BriefService {

    private static final Logger log = LoggerFactory.getLogger(BriefService.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    static final String ROLE = "brief";
    static final String NOTHING_NEW = "nothing new since the last brief";
    static final String RUN_DID_NOT_FINISH = "the brief run for this time did not finish: facts only";

    /**
     * What {@code orange_focus} answers.
     *
     * @param state {@link #READY} (here is a brief), {@link #WRITING} (one is being written;
     *              {@code brief} is the latest so far, if any) or {@link #NONE} (there is none)
     * @param hint  what to do next, or how old the brief is; null when there is nothing to add
     */
    public record Focus(String state, Brief brief, String hint) {
        public static final String READY = "ready";
        public static final String WRITING = "writing";
        public static final String NONE = "none";
    }

    /**
     * How a write ended.
     *
     * @param why null with a brief; else {@link #BUSY} (another brief is being written),
     *            {@link #TAKEN} (the brief time already has its brief), {@link #NO_PROJECT} (no
     *            active project to write about) or {@link #STOPPING} (orange is shutting down)
     */
    record Written(Brief brief, String why) {
        static final String BUSY = "busy";
        static final String TAKEN = "taken";
        static final String NO_PROJECT = "no_project";
        static final String STOPPING = "stopping";

        static Written not(String why) {
            return new Written(null, why);
        }
    }

    /**
     * The model's half of a brief.
     *
     * @param content  the validated lines; null = facts only
     * @param note     why there are no lines; null with content
     * @param runId    the brief run, when one was started
     * @param stopping the run was cut off by orange stopping: no brief is written now
     */
    private record Words(BriefContent content, String note, UUID runId, boolean stopping) {
        static Words none(String note) {
            return new Words(null, note, null, false);
        }
    }

    private final BriefRepository briefs;
    private final BriefSnapshots snapshots;
    private final WorkerRunner runner;
    private final RunRepository runs;
    private final RunsProperties runsProps;
    private final SchedulerPause pause;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final Conventions conventions;
    private final BriefProperties props;
    private final NotifyProperties notify;
    private final HarnessProperties harness;
    private final TransactionTemplate tx;

    /** One brief is written at a time; a second caller is told so instead of waiting. Tests hold it. */
    final ReentrantLock lock = new ReentrantLock();

    public BriefService(BriefRepository briefs, BriefSnapshots snapshots, WorkerRunner runner, RunRepository runs,
                        RunsProperties runsProps, SchedulerPause pause, AttentionService attention,
                        AttentionRepository attentionItems, Conventions conventions, BriefProperties props,
                        NotifyProperties notify, HarnessProperties harness, PlatformTransactionManager txManager) {
        this.briefs = briefs;
        this.snapshots = snapshots;
        this.runner = runner;
        this.runs = runs;
        this.runsProps = runsProps;
        this.pause = pause;
        this.attention = attention;
        this.attentionItems = attentionItems;
        this.conventions = conventions;
        this.props = props;
        this.notify = notify;
        this.harness = harness;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Writes the brief of the brief time that is due at {@code now}, if one is due and has no
     * brief yet. Empty when nothing is due, the brief exists, or it could not be written now.
     */
    public Optional<Brief> scheduled(ZonedDateTime now) {
        ZonedDateTime at = now.withZoneSameInstant(notify.zoneId());
        List<LocalTime> times = BriefSchedule.times(conventions.global(Conventions.BRIEF_TIMES).orElse(null));
        QuietHours quiet = QuietHours.of(conventions.global(Conventions.QUIET_HOURS).orElse(null));
        Optional<ZonedDateTime> slot = BriefSchedule.due(times, at, quiet, props.catchUp());
        if (slot.isEmpty() || briefs.existsForSlot(slot.get().toOffsetDateTime())) return Optional.empty();
        return Optional.ofNullable(write(slot.get(), at).brief());
    }

    /** {@link #focus(boolean, ZonedDateTime)} as of now. */
    public Focus focus(boolean fresh) {
        return focus(fresh, ZonedDateTime.now(notify.zoneId()));
    }

    /**
     * The latest brief, or with {@code fresh} a new one. Reading never starts a run. A fresh brief
     * is written at most once per {@code fresh-min-interval} and waited for {@code focus-wait};
     * when that runs out the write goes on and the answer says so.
     */
    public Focus focus(boolean fresh, ZonedDateTime now) {
        ZonedDateTime at = now.withZoneSameInstant(notify.zoneId());
        Optional<Brief> latest = briefs.latest();
        if (!fresh) {
            return latest.map(b -> new Focus(Focus.READY, b, null))
                    .orElseGet(() -> new Focus(Focus.NONE, null, "no brief yet; ask with fresh: true to write one"));
        }
        Optional<Brief> recent = latest.filter(b -> tooRecent(b, at));
        if (recent.isPresent()) {
            Duration age = Duration.between(recent.get().snapshotAt(), at.toOffsetDateTime());
            return new Focus(Focus.READY, recent.get(), "written " + BriefPrompt.span(age)
                    + " ago; a fresh brief is written at most every " + BriefPrompt.span(props.freshMinInterval()));
        }
        CompletableFuture<Written> writing = new CompletableFuture<>();
        Thread.ofVirtual().name("brief-on-demand").start(() -> {
            try {
                writing.complete(write(null, at));
            } catch (Throwable t) {
                // The caller may have stopped waiting (focus-wait): then this line is all that says so.
                log.warn("the brief that was asked for could not be written: {}", t.toString());
                writing.completeExceptionally(t);
            }
        });
        Written written;
        try {
            written = writing.get(props.focusWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return beingWritten();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return beingWritten();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException("the brief could not be written: " + cause.getMessage(), cause);
        }
        if (written.brief() != null) return new Focus(Focus.READY, written.brief(), null);
        return switch (written.why()) {
            case Written.BUSY -> beingWritten();
            case Written.NO_PROJECT -> new Focus(Focus.NONE, null,
                    "no active project: confirm one (orange_attention) or create one (orange_project_update)");
            default -> new Focus(Focus.NONE, null, null);
        };
    }

    /** Whether {@code brief} is too young for a fresh one to be written at {@code at}. */
    private boolean tooRecent(Brief brief, ZonedDateTime at) {
        return Duration.between(brief.snapshotAt(), at.toOffsetDateTime()).compareTo(props.freshMinInterval()) < 0;
    }

    private Focus beingWritten() {
        return new Focus(Focus.WRITING, briefs.latest().orElse(null),
                "a brief is being written; ask again without fresh in a minute");
    }

    /**
     * Writes one brief: for the brief time {@code slotOrNull}, or one that was asked for (null).
     * A brief that is asked for within {@code fresh-min-interval} of the latest one is that one.
     *
     * @param now the moment the facts are read at, in the user's zone
     */
    Written write(ZonedDateTime slotOrNull, ZonedDateTime now) {
        if (!lock.tryLock()) return Written.not(Written.BUSY);
        try {
            OffsetDateTime slotAt = slotOrNull == null ? null : slotOrNull.toOffsetDateTime();
            if (slotAt != null && briefs.existsForSlot(slotAt)) return Written.not(Written.TAKEN);
            if (slotAt == null) {
                // Asked for while another brief was being written: that one is the fresh brief, not a second run.
                Optional<Brief> recent = briefs.latest().filter(b -> tooRecent(b, now));
                if (recent.isPresent()) return new Written(recent.get(), null);
            }

            // A brief covers what happened since the last scheduled one; briefs that were asked for
            // do not move that window, so a session cannot empty the next scheduled brief.
            ZonedDateTime at = now.truncatedTo(ChronoUnit.MILLIS);
            Optional<Brief> last = briefs.latestScheduled();
            OffsetDateTime since = last.map(Brief::snapshotAt)
                    .orElseGet(() -> at.toOffsetDateTime().minus(props.firstWindow()));
            BriefSnapshot snapshot = snapshots.read(since, at);
            if (snapshot.projects().isEmpty()) return Written.not(Written.NO_PROJECT);

            boolean news = slotOrNull == null || snapshot.hasNews(last.map(BriefService::snapshotOf).orElse(null));
            Words words = words(slotOrNull, snapshot, news, at.getZone());
            if (words.stopping()) {
                log.info("brief {}: orange is stopping; nothing written", name(slotOrNull));
                return Written.not(Written.STOPPING);
            }

            JSONB content = words.content() == null ? null : JSONB.valueOf(words.content().toJson().toString());
            boolean pushable = slotAt != null && news;
            Brief brief = tx.execute(status -> {
                Optional<Brief> inserted = briefs.insert(slotAt, at.toOffsetDateTime(), snapshot.toJsonb(), content,
                        words.note(), words.runId(), pushable, RunTokens.newToken());
                if (inserted.isEmpty()) return null;
                if (words.content() != null) fileUrgent(inserted.get(), snapshot, words.content());
                return inserted.get();
            });
            if (brief == null) return Written.not(Written.TAKEN);
            log.info("brief {} {}: {} ({})", brief.id(), name(slotOrNull),
                    content != null ? "model lines" : words.note(),
                    BriefPrompt.count(snapshot.projects().size(), "project", "projects"));
            return new Written(brief, null);
        } finally {
            lock.unlock();
        }
    }

    /** Whether the model is asked, and what it said. */
    private Words words(ZonedDateTime slot, BriefSnapshot snapshot, boolean news, ZoneId zone) {
        if (!news) return Words.none(NOTHING_NEW);
        Optional<SchedulerPause.Pause> paused = pause.current();
        if (paused.isPresent()) return Words.none(pausedNote(paused.get(), zone));
        String slotKey = slot == null ? null : slotKey(slot);
        // One model attempt per brief time: a run that was started for it and left no brief (a
        // crash, a restart) is not started again.
        if (slotKey != null && runs.existsForSlot(RunKind.BRIEF, slotKey)) return Words.none(RUN_DID_NOT_FINISH);
        return run(slotKey, snapshot);
    }

    /** One brief run in an empty directory of its own; its lines when it left valid ones. */
    private Words run(String slotKey, BriefSnapshot snapshot) {
        Path cwd;
        try {
            cwd = Files.createDirectories(harness.runRoot().resolve("brief").resolve(UUID.randomUUID().toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("brief: could not make the run's directory: {}", e.toString());
            return Words.none(noLines("workspace"));
        }
        RunResult result;
        try {
            ObjectNode input = Json.mapper().createObjectNode().put("trigger", slotKey == null ? "on_demand" : "scheduled");
            if (slotKey != null) input.put("slot", slotKey);
            ArrayNode names = input.putArray("projects");
            snapshot.projectNames().forEach(names::add);
            String fence = "brief-text-" + UUID.randomUUID();
            result = runner.runDirect(RunKind.BRIEF, ROLE, cwd, BriefPrompt.prompt(snapshot, fence),
                    runsProps.timeout(RunKind.BRIEF), JSONB.valueOf(input.toString()));
        } finally {
            delete(cwd);
        }
        if ("coordinator_restart".equals(result.reason())) return new Words(null, null, result.runId(), true);
        if (result.reason() != null) return new Words(null, noLines(result.reason()), result.runId(), false);
        if (!(result.outcome() instanceof RunOutcome.Brief said)) {
            return new Words(null, noLines("no_outcome"), result.runId(), false);
        }
        try {
            // submit_brief validated it against the run's input; this is the same list, checked again
            // because the stored outcome is what the brief is written from.
            return new Words(BriefContent.parse(said.content(), snapshot.projectNames()), null, result.runId(), false);
        } catch (IllegalArgumentException e) {
            log.warn("brief run {}: its stored brief is not valid: {}", result.runId(), e.getMessage());
            return new Words(null, noLines("invalid_brief"), result.runId(), false);
        }
    }

    /**
     * The model's urgent entries become {@code normal} attention items: at most one unresolved per
     * project, never pushed by themselves (the brief's own push carries them), and an item that is
     * already there is left as it is.
     */
    private void fileUrgent(Brief brief, BriefSnapshot snapshot, BriefContent content) {
        for (BriefContent.Urgent urgent : content.urgent()) {
            Optional<ProjectFacts> project = snapshot.projects().stream()
                    .filter(p -> urgent.project().equals(p.name())).findFirst();
            UUID projectId = project.map(p -> uuid(p.id())).orElse(null);
            if (projectId == null) continue;
            if (attentionItems.hasUnresolved(projectId, AttentionItem.BRIEF_URGENT)) continue;
            // One line each with nothing unseen in it, whatever the content went through before: these
            // go to the inbox and the log, not only to the brief's own text.
            String title = BriefSnapshot.one(urgent.title(), BriefContent.MAX_TITLE);
            String why = BriefSnapshot.one(urgent.why(), BriefContent.MAX_WHY);
            attention.raise(AttentionItem.BRIEF_URGENT, "brief:" + brief.id() + ":" + projectId, AttentionItem.NORMAL,
                    projectId, null, project.get().name() + ": " + title, why);
        }
    }

    /** How a brief time is written in a run's input: its instant, so a change of zone does not hide the run. */
    static String slotKey(ZonedDateTime slot) {
        return slot.toInstant().toString();
    }

    static String pausedNote(SchedulerPause.Pause paused, ZoneId zone) {
        String until = paused.until() == null ? "" : " until " + CLOCK.format(paused.until().atZoneSameInstant(zone));
        return "orange is paused (" + paused.reason() + until + "): facts only";
    }

    static String noLines(String reason) {
        return "no model lines (" + reason + "): facts only";
    }

    private static String name(ZonedDateTime slot) {
        return slot == null ? "on demand" : "for " + slot.toOffsetDateTime();
    }

    /** The facts of the brief before; null when they cannot be read, which then counts as no brief before. */
    private static BriefSnapshot snapshotOf(Brief brief) {
        try {
            return BriefSnapshot.parse(brief.snapshot());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuid(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Removes the run's directory and whatever the agent left in it, best effort. */
    private static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            log.warn("brief: could not delete {}: {}", dir, e.toString());
        }
    }
}
