package com.ai.orange.planner;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionResolver;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.Repo;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.worktree.Git;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plans of a project (remote-first spec §7.2): the user's request is planned in the background on a
 * read-only clone of the project's repo, with the project's facts in the prompt; then
 * {@link PlanApproval} dispatches the plan at once or files one urgent {@code plan_approval} item,
 * which the user answers with approve, drop or a note that revises the plan.
 *
 * <p>The planner takes minutes, so no transaction is open while it runs: only recording what it
 * delivered, the decision and the dispatch are one transaction, under the plan's row lock. An answer
 * acts under the same lock, so a plan dispatches exactly once.
 *
 * <p>Only the user puts evidence in force (builder's call 12): commands the planner proposes become the
 * project's {@code evidence} convention when the user approves the plan that carries them, and only
 * when none is in force.
 */
@Service
public class PlanService implements AttentionResolver {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    /** The convention that overrides the planner's judgement: {@code auto}, {@code always} or {@code never}. */
    public static final String PLAN_APPROVAL = "plan-approval";
    static final String RESTARTED = "coordinator restarted";
    private static final String APPROVAL_KEY = AttentionItem.PLAN_APPROVAL + ":";
    private static final int MAX_ARCHITECTURE = 100_000;
    private static final int MAX_TITLE = 240;

    private final PlanRepository plans;
    private final PlannerService planner;
    private final PlannerProperties props;
    private final PlanGraphService graph;
    private final ProjectService projects;
    private final SpaceService space;
    private final Conventions conventions;
    private final WorkConventions work;
    private final Mirrors mirrors;
    private final AttentionService attention;
    private final SchedulerPause pause;
    private final TransactionTemplate tx;

    /** Plans this instance is planning right now: never failed as leftovers of a restart. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PlanService(PlanRepository plans, PlannerService planner, PlannerProperties props, PlanGraphService graph,
                       ProjectService projects, SpaceService space, Conventions conventions, WorkConventions work,
                       Mirrors mirrors, AttentionService attention, SchedulerPause pause,
                       PlatformTransactionManager txManager) {
        this.plans = plans;
        this.planner = planner;
        this.props = props;
        this.graph = graph;
        this.projects = projects;
        this.space = space;
        this.conventions = conventions;
        this.work = work;
        this.mirrors = mirrors;
        this.attention = attention;
        this.pause = pause;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Starts planning {@code request} for a project and returns at once with the plan, {@code planning}.
     * It then dispatches or asks within minutes.
     *
     * @param projectRef   the project's id or name
     * @param milestoneRef an active milestone of the project, by id or by its text; null or blank for none
     * @throws IllegalArgumentException when the request is blank, the project is unknown, not active or has
     *                                  no GitHub repo, the milestone is not an active one of the project, or
     *                                  the scheduler is paused; nothing is planned then
     */
    public Plan start(String projectRef, String request, String milestoneRef) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("a plan needs a request: say what to build");
        }
        Project project = projects.find(projectRef)
                .orElseThrow(() -> new IllegalArgumentException("no such project: " + projectRef));
        if (!Project.ACTIVE.equals(project.status())) {
            throw new IllegalArgumentException("project " + project.name() + " is " + project.status()
                    + ", not active");
        }
        if (projects.githubRepos(project.id()).isEmpty()) {
            throw new IllegalArgumentException("project " + project.name() + " has no GitHub repo; attach one with "
                    + "orange_project_update add_repo owner/name");
        }
        UUID milestone = null;
        if (milestoneRef != null && !milestoneRef.isBlank()) {
            milestone = milestone(project.id(), milestoneRef)
                    .orElseThrow(() -> new IllegalArgumentException("no active milestone " + milestoneRef.strip()
                            + " in project " + project.name()));
        }
        refuseWhilePaused();
        Plan plan = plans.insert(project.id(), request.strip(), null, null, milestone);
        log.info("plan {} for {}: planning", plan.id(), project.name());
        spawn(plan.id());
        return plan;
    }

    /**
     * Plans {@code planId}, which must be {@code planning}, and decides: dispatch or ask. Takes the
     * planner's minutes; holds no transaction meanwhile. Never throws: a plan that cannot be planned
     * fails and files a {@code plan_failed} item.
     */
    void plan(UUID planId) {
        inFlight.add(planId);
        try {
            Plan plan = plans.findById(planId).orElse(null);
            if (plan == null || !Plan.PLANNING.equals(plan.status())) return;
            PlannerService.PlanResult result;
            Path dir = Path.of(props.outputRoot()).toAbsolutePath().normalize().resolve(planId.toString());
            try {
                Files.createDirectories(dir);
                List<Repo> repos = projects.githubRepos(plan.projectId());
                String base = repos.isEmpty() ? null : baseBranch(plan.projectId(), repos.getFirst());
                boolean cloned = !repos.isEmpty() && cloneRepo(planId, repos.getFirst(), base, dir.resolve("repo"));
                Revision revision = revision(plan);
                String prompt = PlannerPrompt.build(plan.request(), revision.notes(), revision.previous(),
                        facts(plan.projectId(), repos, base, cloned));
                result = planner.plan(dir, prompt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(planId, "interrupted", null);
                return;
            } catch (IOException | RuntimeException e) {
                log.error("plan {}: could not plan: {}", planId, e.toString());
                fail(planId, "could not plan: " + oneLine(e.getMessage()), null);
                return;
            } finally {
                // The clone was only for the planner to read; plan.json and architecture.md stay.
                deleteQuietly(dir.resolve("repo"));
            }
            if (!"success".equals(result.status())) {
                fail(planId, result.status(), result.runId());
                return;
            }
            try {
                tx.executeWithoutResult(s -> decide(planId, result));
            } catch (RuntimeException e) {
                // Rolled back: the plan is still planning and no task exists.
                log.error("plan {}: could not record or dispatch it: {}", planId, e.toString());
                fail(planId, "could not dispatch: " + oneLine(e.getMessage()), result.runId());
            }
        } catch (RuntimeException e) {
            log.error("plan {}: failed unexpectedly", planId, e);
        } finally {
            inFlight.remove(planId);
        }
    }

    /** Records what the planner delivered and dispatches it or asks the user, in the caller's transaction. */
    private void decide(UUID planId, PlannerService.PlanResult result) {
        Plan plan = plans.lock(planId).orElse(null);
        if (plan == null || !Plan.PLANNING.equals(plan.status())) return;
        Project project = projects.find(plan.projectId().toString()).orElseThrow();
        PlanJson pj = result.plan();
        // Commands that could read one way and run another are not shown to the user to approve.
        String refused = PlanJson.evidenceRefusal(pj.evidence()).orElse(null);
        if (refused != null) {
            log.warn("plan {}: refused the planner's proposed evidence commands: {}", planId, refused);
            pj = pj.withoutEvidence();
        }
        JSONB json = json(pj);
        String architecture = architecture(result.architectureMd());
        UUID milestone = plan.milestoneId() != null ? plan.milestoneId()
                : pj.milestone() == null ? null : milestoneById(plan.projectId(), pj.milestone()).orElse(null);
        String convention = conventions.resolve(PLAN_APPROVAL, plan.projectId(), null)
                .map(v -> v.asText(PlanApproval.AUTO)).orElse(PlanApproval.AUTO);
        boolean evidenceInForce = evidenceInForce(plan.projectId());
        PlanApproval.Decision decision = PlanApproval.decide(convention, evidenceInForce, pj, props.askAboveTasks());
        if (!decision.ask()) {
            plans.finishPlanning(planId, Plan.PLANNING, false, null, json, architecture, null, result.runId(), milestone);
            int n = graph.ingest(pj, plan.projectId().toString(), repoOf(plan.projectId(), pj), planId, milestone).size();
            plans.move(planId, Plan.PLANNING, Plan.DISPATCHED);
            log.info("plan {} for {}: dispatched {} tasks", planId, project.name(), n);
            return;
        }
        plans.finishPlanning(planId, Plan.ASKING, true, decision.reason(), json, architecture, null, result.runId(),
                milestone);
        AttentionItem item = attention.raise(AttentionItem.PLAN_APPROVAL, APPROVAL_KEY + planId, AttentionItem.URGENT,
                plan.projectId(), null, null,
                title("Approve the plan for " + project.name() + "? " + decision.reason()),
                PlanText.body(plan.request(), decision.reason(), pj, evidenceInForce, refused), "approve");
        plans.setAttention(planId, item.id());
        log.info("plan {} for {}: asks first ({})", planId, project.name(), decision.reason());
    }

    @Override
    public Set<String> kinds() {
        return Set.of(AttentionItem.PLAN_APPROVAL, AttentionItem.PLAN_FAILED);
    }

    /**
     * The user's answer to a plan's item, in {@link AttentionService#resolve}'s transaction. To a
     * {@code plan_approval}: approve ({@code approve}, {@code yes}, {@code ok}) dispatches the plan, after
     * putting its proposed evidence in force when the project has none; {@code drop} or {@code no} drops it;
     * any other text drops it and plans the request again with that text as the revision note. A plan
     * that no longer asks is left as it is. A {@code plan_failed} item only resolves.
     *
     * @throws IllegalArgumentException for a blank answer, or a revision while the scheduler is paused
     */
    @Override
    public void onResolve(AttentionItem item, String answer) {
        if (!AttentionItem.PLAN_APPROVAL.equals(item.kind())) return;
        UUID planId = planOf(item);
        if (planId == null) return;
        Plan plan = plans.lock(planId).orElse(null);
        if (plan == null || !Plan.ASKING.equals(plan.status())) return;
        String a = answer == null ? "" : answer.strip();
        switch (a.toLowerCase(Locale.ROOT)) {
            case "approve", "yes", "ok" -> approve(plan);
            case "drop", "no" -> {
                plans.move(planId, Plan.ASKING, Plan.DROPPED);
                log.info("plan {}: dropped by the user", planId);
            }
            case "" -> throw new IllegalArgumentException("answer approve, drop, or what to change");
            default -> revise(plan, a);
        }
    }

    private void approve(Plan plan) {
        PlanJson pj = parse(plan.plan());
        if (!pj.evidence().isEmpty() && !evidenceInForce(plan.projectId())) {
            // The user's approval is the user putting these commands in force (builder's call 12).
            space.setConvention(plan.projectId(), null, WorkConventions.EVIDENCE,
                    Json.mapper().valueToTree(pj.evidence()), SpaceService.Author.USER);
        }
        int n = graph.ingest(pj, plan.projectId().toString(), repoOf(plan.projectId(), pj), plan.id(),
                plan.milestoneId()).size();
        plans.move(plan.id(), Plan.ASKING, Plan.DISPATCHED);
        log.info("plan {}: approved, dispatched {} tasks", plan.id(), n);
    }

    private void revise(Plan plan, String note) {
        refuseWhilePaused();
        plans.move(plan.id(), Plan.ASKING, Plan.DROPPED);
        Plan next = plans.insert(plan.projectId(), plan.request(), note, plan.id(), plan.milestoneId());
        log.info("plan {}: revised by the user as plan {}", plan.id(), next.id());
        afterCommit(() -> spawn(next.id()));
    }

    /**
     * The repo a plan's tasks go to unless a task names another: the plan's own {@code repo}, else the
     * repo that was cloned for the planner (the project's first GitHub repo).
     */
    private String repoOf(UUID projectId, PlanJson pj) {
        if (pj.repo() != null && !pj.repo().isBlank()) return pj.repo();
        List<Repo> repos = projects.githubRepos(projectId);
        return repos.isEmpty() ? null : repos.getFirst().key().toString();
    }

    /** What a revision tells the planner: every note along its chain, oldest first, and the plan it revises. */
    private record Revision(List<String> notes, PlanJson previous) {}

    private Revision revision(Plan plan) {
        List<String> notes = new ArrayList<>();
        PlanJson previous = null;
        Set<UUID> seen = new HashSet<>();
        Plan at = plan;
        while (at != null && seen.add(at.id())) {
            if (at.note() != null && !at.note().isBlank()) notes.add(at.note());
            if (at.revisesId() == null) break;
            Plan before = plans.findById(at.revisesId()).orElse(null);
            if (previous == null && before != null && before.plan() != null) {
                try {
                    previous = parse(before.plan());
                } catch (IllegalStateException unreadable) {
                    // the revision is planned without it
                }
            }
            at = before;
        }
        return new Revision(notes.reversed(), previous);
    }

    /** At start: a plan left {@code planning} by a previous instance will never finish; it fails and says so. */
    @EventListener(ApplicationReadyEvent.class)
    public void failLeftovers() {
        for (Plan p : plans.findByStatus(List.of(Plan.PLANNING))) {
            if (inFlight.contains(p.id())) continue;
            fail(p.id(), RESTARTED, null);
        }
    }

    /** The plan fails with {@code failure} (when it is still planning) and files a normal {@code plan_failed} item. */
    private void fail(UUID planId, String failure, UUID runId) {
        try {
            tx.executeWithoutResult(s -> {
                Plan plan = plans.lock(planId).orElse(null);
                if (plan == null || !Plan.PLANNING.equals(plan.status())) return;
                plans.finishPlanning(planId, Plan.FAILED, null, null, null, null, failure, runId, plan.milestoneId());
                String name = projects.find(plan.projectId().toString()).map(Project::name).orElse("a project");
                attention.raise(AttentionItem.PLAN_FAILED, AttentionItem.PLAN_FAILED + ":" + planId, AttentionItem.NORMAL,
                        plan.projectId(), null, null, title("Plan for " + name + " failed: " + failure),
                        "Request: " + oneLine(plan.request()) + "\nThe planner did not deliver: " + failure
                                + ".\nAsk again with orange_plan, or resolve this item.", null);
                log.warn("plan {} for {} failed: {}", planId, name, failure);
            });
        } catch (RuntimeException e) {
            log.error("plan {}: could not record its failure ({}): {}", planId, failure, e.toString());
        }
    }

    // ─────────────────────────── helpers ────────────────────────────────

    private void spawn(UUID planId) {
        inFlight.add(planId);
        Thread.ofVirtual().name("plan-" + planId).start(() -> plan(planId));
    }

    private void refuseWhilePaused() {
        pause.current().ifPresent(p -> {
            throw new IllegalArgumentException("the scheduler is paused (" + p.reason()
                    + "): orange plans nothing until it is resumed");
        });
    }

    private static void afterCommit(Runnable r) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            r.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                r.run();
            }
        });
    }

    /**
     * A clone of {@code origin/<base>} of the repo's mirror at {@code into}, fetched first. orange runs
     * no git there afterwards. False when it cannot be made: the planner then plans without it.
     */
    private boolean cloneRepo(UUID planId, Repo repo, String base, Path into) {
        try {
            mirrors.fetch(repo.key());
        } catch (RuntimeException e) {
            log.warn("plan {}: could not fetch {}; planning on the mirror as it is: {}", planId, repo.key(),
                    e.getMessage());
        }
        try {
            Path mirror = mirrors.path(repo.key());
            Files.createDirectories(into);
            Git.run(into, Git.QUICK, "init", "--quiet");
            // Into FETCH_HEAD: the new repository's unborn HEAD may already name the base branch.
            Git.run(into, Git.SLOW, "fetch", "--quiet", "--no-tags", mirror.toString(), "refs/remotes/origin/" + base);
            // Links the repository holds become plain files: none points the planner out of repo/.
            Git.run(into, Git.SLOW, "-c", "core.symlinks=false", "checkout", "--quiet", "-B", base, "FETCH_HEAD");
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("plan {}: planning without the repository; it could not be cloned: {}", planId, e.getMessage());
            deleteQuietly(into);
            return false;
        }
    }

    /** The branch a task of the repo integrates onto, as {@code RepoTaskBases} finds it. */
    private String baseBranch(UUID projectId, Repo repo) {
        return work.baseBranch(projectId, null)
                .or(() -> Optional.ofNullable(repo.defaultBranch()).filter(b -> !b.isBlank()))
                .or(() -> {
                    try {
                        return mirrors.defaultBranch(repo.key());
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                })
                .orElse("main");
    }

    private PlannerPrompt.Facts facts(UUID projectId, List<Repo> repos, String base, boolean cloned) {
        String name = projects.find(projectId.toString()).map(Project::name).orElse(projectId.toString());
        List<SpaceItem> active = space.current(projectId).stream()
                .filter(i -> SpaceItem.ACTIVE.equals(i.status()) && i.sessionId() == null).toList();
        List<PlannerPrompt.Milestone> milestones = active.stream()
                .filter(i -> SpaceItem.MILESTONE.equals(i.kind()))
                .map(i -> new PlannerPrompt.Milestone(i.id().toString(), text(i),
                        i.dueAt() == null ? null : i.dueAt().toLocalDate().toString()))
                .toList();
        List<String> evidence;
        try {
            evidence = work.evidence(projectId, null).stream().map(c -> c.command()).toList();
        } catch (IllegalArgumentException broken) {
            evidence = List.of();
        }
        String review = conventions.resolve(WorkConventions.REVIEW, projectId, null).map(JsonNode::asText).orElse(null);
        return new PlannerPrompt.Facts(name, repos.stream().map(r -> r.key().toString()).toList(), base,
                texts(active, SpaceItem.GOAL), milestones, texts(active, SpaceItem.DECISION),
                texts(active, SpaceItem.NOTE), evidence, review, cloned,
                repos.isEmpty() ? null : repos.getFirst().key().toString());
    }

    /**
     * Whether the project's work can be verified without the planner's proposal: it has evidence
     * commands in force, or its {@code done} convention asks for none. A convention that is not a list
     * of commands counts as in force: the user set it, and the verify run says what is wrong with it.
     */
    private boolean evidenceInForce(UUID projectId) {
        if (!work.done(projectId, null).contains(WorkConventions.EVIDENCE)) return true;
        try {
            return !work.evidence(projectId, null).isEmpty();
        } catch (IllegalArgumentException broken) {
            return true;
        }
    }

    /** An active milestone of the project, by id or by its text (ignoring case). */
    private Optional<UUID> milestone(UUID projectId, String ref) {
        Optional<UUID> byId = milestoneById(projectId, ref);
        if (byId.isPresent()) return byId;
        List<SpaceItem> named = space.current(projectId).stream()
                .filter(i -> isActiveMilestone(i, projectId) && text(i).equalsIgnoreCase(ref.strip())).toList();
        return named.size() == 1 ? Optional.of(named.getFirst().id()) : Optional.empty();
    }

    private Optional<UUID> milestoneById(UUID projectId, String ref) {
        UUID id;
        try {
            id = UUID.fromString(ref.strip());
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
        return space.find(id).filter(i -> isActiveMilestone(i, projectId)).map(SpaceItem::id);
    }

    private static boolean isActiveMilestone(SpaceItem i, UUID projectId) {
        return SpaceItem.MILESTONE.equals(i.kind()) && SpaceItem.ACTIVE.equals(i.status())
                && projectId.equals(i.projectId()) && i.sessionId() == null;
    }

    private static List<String> texts(List<SpaceItem> items, String kind) {
        return items.stream().filter(i -> kind.equals(i.kind())).map(PlanService::text).toList();
    }

    private static String text(SpaceItem i) {
        try {
            return Json.mapper().readTree(i.body().data()).path("text").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static UUID planOf(AttentionItem item) {
        String key = item.dedupeKey();
        if (key == null || !key.startsWith(APPROVAL_KEY)) return null;
        try {
            return UUID.fromString(key.substring(APPROVAL_KEY.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JSONB json(PlanJson plan) {
        try {
            return JSONB.valueOf(Json.mapper().writeValueAsString(plan));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the plan as JSON", e);
        }
    }

    private static PlanJson parse(JSONB plan) {
        if (plan == null) throw new IllegalStateException("the plan holds no plan.json");
        try {
            return Json.mapper().readValue(plan.data(), PlanJson.class);
        } catch (IOException e) {
            throw new IllegalStateException("the stored plan cannot be read: " + e.getMessage(), e);
        }
    }

    private static String architecture(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String s = Files.readString(file);
            return s.length() > MAX_ARCHITECTURE ? s.substring(0, MAX_ARCHITECTURE) : s;
        } catch (IOException e) {
            return null;
        }
    }

    private static String title(String s) {
        String t = oneLine(s);
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE - 1) + "…" : t;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    private static void deleteQuietly(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(f);
        } catch (IOException e) {
            log.warn("could not remove {}: {}", p, e.getMessage());
        }
    }
}
