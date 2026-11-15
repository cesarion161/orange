package com.ai.orange.project;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.repo.RepoProperties;
import com.ai.orange.repo.RepoRepository;
import com.ai.orange.session.SessionRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects and their repos. A session's repo decides its project (spec decision 9); a repo orange
 * has not seen becomes a {@code proposed} project and a question for the user. A repo on GitHub is
 * its remote (remote-first spec §7.1): every checkout of it, on any machine, is the same project.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projects;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final SessionRepository sessions;
    private final RepoRepository repos;
    private final RepoProperties repoProps;

    public ProjectService(ProjectRepository projects, AttentionService attention, AttentionRepository attentionItems,
                          SessionRepository sessions, RepoRepository repos, RepoProperties repoProps) {
        this.sessions = sessions;
        this.repos = repos;
        this.repoProps = repoProps;
        this.projects = projects;
        this.attention = attention;
        this.attentionItems = attentionItems;
    }

    static String proposedKey(UUID projectId) {
        return "project:" + projectId + ":proposed";
    }

    /**
     * The project of a repo on a node. {@code repoRoot} is the main checkout, also for sessions in one
     * of its worktrees. A checkout orange knows keeps its project. A new checkout of a GitHub repo
     * orange knows joins that repo's project; any other new checkout becomes a {@code proposed}
     * project. Either way a checkout whose remote is on GitHub is linked to its repo.
     */
    @Transactional
    public Project resolve(String nodeId, String repoRoot, String remoteUrl, String defaultBranch) {
        Optional<RepoKey> key = RepoKey.parse(remoteUrl, repoProps.githubUrl());
        Optional<ProjectRepo> known = projects.findRepo(nodeId, repoRoot);
        if (known.isPresent()) {
            ProjectRepo checkout = known.get();
            if (key.isPresent() && checkout.repoId() == null) {
                Repo repo = repos.insertIfAbsent(checkout.projectId(), key.get(), defaultBranch);
                projects.linkRepo(checkout.id(), repo.id());
            }
            if (checkout.repoId() != null) repos.setDefaultBranchIfNone(checkout.repoId(), defaultBranch);
            return projects.findById(checkout.projectId()).orElseThrow();
        }

        Optional<Repo> repo = key.flatMap(repos::findByKey);
        if (repo.isPresent()) {
            ProjectRepo checkout = projects.putRepo(repo.get().projectId(), nodeId, repoRoot, remoteUrl, defaultBranch);
            projects.linkRepo(checkout.id(), repo.get().id());
            repos.setDefaultBranchIfNone(repo.get().id(), defaultBranch);
            log.info("new checkout {} on node {} of {}: project {}", repoRoot, nodeId, repo.get().key(),
                    repo.get().projectId());
            return projects.findById(repo.get().projectId()).orElseThrow();
        }

        Project project = projects.insert(freeName(nameOf(repoRoot)), Project.PROPOSED, null);
        ProjectRepo checkout = projects.putRepo(project.id(), nodeId, repoRoot, remoteUrl, defaultBranch);
        key.ifPresent(k -> projects.linkRepo(checkout.id(), repos.insertIfAbsent(project.id(), k, defaultBranch).id()));
        log.info("new repo {} on node {}: proposed project {} ({})", repoRoot, nodeId, project.name(), project.id());
        attention.raise(AttentionItem.PROJECT_PROPOSED, proposedKey(project.id()), AttentionItem.NORMAL, project.id(),
                null, "New project: " + project.name(),
                "A session is working in " + repoRoot + ", a repo orange has not seen before.\n"
                        + "Answer \"confirm\" to track it as project \"" + project.name() + "\", or \"ignore\" to "
                        + "archive it so its sessions raise nothing. It is watched like any project until then.");
        return project;
    }

    /** A project's GitHub repos, oldest first. */
    public List<Repo> githubRepos(UUID projectId) {
        return repos.ofProject(projectId);
    }

    /** The GitHub repo a checkout is of, if its remote is on GitHub. */
    public Optional<Repo> repoOfCheckout(String nodeId, String repoRoot) {
        if (nodeId == null || repoRoot == null) return Optional.empty();
        return projects.findRepo(nodeId, repoRoot).map(ProjectRepo::repoId).flatMap(repos::findById);
    }

    /**
     * Attaches a GitHub repo to a project, taking it from the project that had it, with its
     * checkouts and their sessions.
     */
    @Transactional
    public Repo addGithubRepo(UUID projectId, RepoKey key) {
        projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("no such project: " + projectId));
        Repo repo = repos.insertIfAbsent(projectId, key, null);
        if (!repo.projectId().equals(projectId)) {
            repos.moveToProject(repo.id(), projectId);
            for (ProjectRepo checkout : projects.checkoutsOf(repo.id())) {
                sessions.moveToProject(checkout.nodeId(), checkout.repoRoot(), projectId);
            }
            log.info("repo {} moved to project {}", key, projectId);
        }
        return repos.findById(repo.id()).orElseThrow();
    }

    /**
     * Where a task of {@code projectRef} is done: the repo named, or the project's only repo. A blank
     * project is no placement (the legacy base repo).
     *
     * @throws IllegalArgumentException naming what is missing or ambiguous
     */
    public TaskPlacement placeTask(String projectRef, String repoKey) {
        boolean repoNamed = repoKey != null && !repoKey.isBlank();
        if (projectRef == null || projectRef.isBlank()) {
            if (repoNamed) throw new IllegalArgumentException("a repo is named only together with its project");
            return TaskPlacement.NONE;
        }
        Project project = find(projectRef).orElseThrow(() -> new IllegalArgumentException("no such project: " + projectRef));
        List<Repo> own = repos.ofProject(project.id());
        if (repoNamed) {
            RepoKey key = RepoKey.of(repoKey);
            return own.stream().filter(r -> r.key().equals(key)).findFirst()
                    .map(r -> new TaskPlacement(project.id(), r.id()))
                    .orElseThrow(() -> new IllegalArgumentException(key + " is not a repo of project " + project.name()));
        }
        if (own.isEmpty()) {
            throw new IllegalArgumentException("project " + project.name() + " has no GitHub repo; attach one with "
                    + "orange_project_update add_repo owner/name");
        }
        if (own.size() > 1) {
            throw new IllegalArgumentException("project " + project.name() + " has " + own.size()
                    + " repos; name one with repo");
        }
        return new TaskPlacement(project.id(), own.getFirst().id());
    }

    /** A project by id or by name. */
    public Optional<Project> find(String ref) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        try {
            return projects.findById(UUID.fromString(ref.strip()));
        } catch (IllegalArgumentException notAnId) {
            return projects.findByName(ref.strip());
        }
    }

    @Transactional
    public Project create(String name, String summary) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a project needs a name");
        if (projects.nameTaken(name.strip())) throw new IllegalArgumentException("project name is taken: " + name);
        return projects.insert(name.strip(), Project.ACTIVE, blankToNull(summary));
    }

    /**
     * Changes what is given (null = keep). Leaving {@code proposed} answers the question orange
     * filed when it first saw the repo.
     */
    @Transactional
    public Project update(UUID id, String name, String status, String summary) {
        Project p = projects.findById(id).orElseThrow(() -> new IllegalArgumentException("no such project: " + id));
        String newName = name == null || name.isBlank() ? p.name() : name.strip();
        String newStatus = status == null || status.isBlank() ? p.status() : status.strip();
        if (!Project.STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("status must be one of " + Project.STATUSES);
        }
        if (!newName.equalsIgnoreCase(p.name()) && projects.nameTaken(newName)) {
            throw new IllegalArgumentException("project name is taken: " + newName);
        }
        projects.update(id, newName, newStatus, summary == null ? p.summary() : blankToNull(summary));
        if (Project.PROPOSED.equals(p.status()) && !Project.PROPOSED.equals(newStatus)) {
            attentionItems.resolveByKey(proposedKey(id), "user", newStatus);
        }
        return projects.findById(id).orElseThrow();
    }

    /** Attaches a repo to a project, taking it (and its sessions) from the project that had it. */
    @Transactional
    public ProjectRepo addRepo(UUID projectId, String nodeId, String repoRoot) {
        if (repoRoot == null || repoRoot.isBlank()) throw new IllegalArgumentException("repo path is blank");
        String node = nodeId == null || nodeId.isBlank() ? "local" : nodeId.strip();
        ProjectRepo repo = projects.putRepo(projectId, node, repoRoot.strip(), null, null);
        sessions.moveToProject(node, repo.repoRoot(), projectId);
        return repo;
    }

    static String nameOf(String repoRoot) {
        Path name = Path.of(repoRoot).getFileName();
        return name == null ? repoRoot : name.toString();
    }

    private String freeName(String base) {
        String name = base;
        for (int n = 2; projects.nameTaken(name); n++) name = base + "-" + n;
        return name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
