package com.ai.orange.project;

import com.ai.orange.evidence.EvidenceCommands;
import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.repo.GithubRepos;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.repo.RepoProperties;
import com.ai.orange.repo.RepoRepository;
import com.ai.orange.worktree.GitException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A project the user asks for from the steering tab ({@code orange_project_create}; remote-first spec
 * §7.1): active at once, with no proposal item, on an existing GitHub repo or on one orange creates.
 * The repo is cloned (its mirror) before anything is recorded, so a repo orange cannot read creates
 * nothing. Evidence commands given here are the user's own word and are put in force.
 */
@Service
public class ProjectCreation {

    private static final Logger log = LoggerFactory.getLogger(ProjectCreation.class);
    /** A repo GitHub has just created may take a moment before it can be cloned. */
    static final int CLONE_ATTEMPTS = 3;
    static final Duration CLONE_RETRY = Duration.ofSeconds(2);

    private final ProjectService projects;
    private final RepoRepository repos;
    private final RepoProperties repoProps;
    private final GithubRepos github;
    private final Mirrors mirrors;
    private final SpaceService space;
    private final TransactionTemplate tx;

    public ProjectCreation(ProjectService projects, RepoRepository repos, RepoProperties repoProps, GithubRepos github,
                           Mirrors mirrors, SpaceService space, PlatformTransactionManager txManager) {
        this.projects = projects;
        this.repos = repos;
        this.repoProps = repoProps;
        this.github = github;
        this.mirrors = mirrors;
        this.space = space;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param repoRef    {@code owner/name}; with {@code createRepo} also a bare name (the owner is the one
     *                   orange has a token for), or blank for a repo named after the project; blank without
     *                   {@code createRepo} is a project of no repo
     * @param createRepo create the repo on GitHub first (private, with a first commit)
     * @param evidence   the evidence convention to put in force (a list of commands); null for none
     * @throws IllegalArgumentException saying what to correct; nothing is recorded then (a repo GitHub
     *                                  already created stays)
     */
    public Project create(String name, String summary, String repoRef, boolean createRepo, JsonNode evidence) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a project needs a name");
        String projectName = name.strip();
        projects.find(projectName).ifPresent(p -> {
            throw new IllegalArgumentException("project name is taken: " + projectName + (Project.PROPOSED.equals(p.status())
                    ? " (orange proposed it from a session; activate it with orange_project_update project="
                            + p.name() + " status=active)" : ""));
        });
        if (evidence != null && !evidence.isNull()) checkEvidence(evidence);

        RepoKey wanted = key(projectName, repoRef == null ? "" : repoRef.strip(), createRepo);
        RepoKey key = wanted;
        if (key != null) {
            repos.findByKey(wanted).filter(r -> r.projectId() != null).ifPresent(r -> {
                Project owner = projects.find(r.projectId().toString()).orElse(null);
                String ownerName = owner == null ? r.projectId().toString() : owner.name();
                throw new IllegalArgumentException(wanted + " is already a repo of project " + ownerName
                        + (owner != null && Project.PROPOSED.equals(owner.status())
                                ? ", which orange proposed from a session: activate it with orange_project_update project="
                                        + ownerName + " status=active (name= renames it)"
                                : "; move it with orange_project_update add_repo"));
            });
            if (createRepo) {
                try {
                    key = github.create(key.owner(), key.name());
                } catch (GithubRepos.GithubException e) {
                    throw new IllegalArgumentException("could not create " + key + " on GitHub: " + e.getMessage());
                }
            }
            clone(key, createRepo ? CLONE_ATTEMPTS : 1);
        }

        RepoKey repo = key;
        Project created = tx.execute(s -> {
            Project p = projects.create(projectName, summary);
            if (repo != null) projects.addGithubRepo(p.id(), repo);
            if (evidence != null && !evidence.isNull()) {
                space.setConvention(p.id(), null, "evidence", evidence, SpaceService.Author.USER);
            }
            return p;
        });
        log.info("project {} created{}", projectName, repo == null ? "" : " on " + repo);
        return created;
    }

    private RepoKey key(String projectName, String ref, boolean createRepo) {
        if (ref.contains("/")) return RepoKey.of(ref);
        if (!createRepo) {
            if (ref.isEmpty()) return null;
            throw new IllegalArgumentException("repo is owner/name, not \"" + ref
                    + "\" (or pass create: true to make a new repo of that name)");
        }
        List<String> owners = repoProps.owners();
        if (owners.size() != 1) {
            throw new IllegalArgumentException(owners.isEmpty()
                    ? "orange has no GitHub token (ORANGE_GITHUB_TOKENS), so it cannot create a repo"
                    : "orange has tokens for " + owners.size() + " owners: give repo as owner/name");
        }
        String repoName = ref.isEmpty() ? projectName : ref;
        try {
            return new RepoKey(owners.getFirst(), repoName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + repoName + "\" is not a GitHub repo name: give repo, "
                    + "letters, digits, '.', '-' and '_' only");
        }
    }

    private void clone(RepoKey key, int attempts) {
        for (int attempt = 1; ; attempt++) {
            try {
                mirrors.ensure(key);
                return;
            } catch (GitException e) {
                if (attempt >= attempts) {
                    throw new IllegalArgumentException("orange cannot read " + key + " from GitHub: " + lastLine(e.output())
                            + "; check the name, and that the token for " + key.owner() + " can read it");
                }
                try {
                    Thread.sleep(CLONE_RETRY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalArgumentException("interrupted while cloning " + key);
                }
            }
        }
    }

    private static void checkEvidence(JsonNode evidence) {
        List<EvidenceProperties.Command> commands = EvidenceCommands.parse(evidence);
        if (commands.isEmpty() || commands.stream().anyMatch(c -> c.command().isBlank())) {
            throw new IllegalArgumentException("evidence is a list of shell commands, none of them blank, such as "
                    + "[\"./gradlew test\"]; leave it out for none");
        }
    }

    private static String lastLine(String output) {
        if (output == null || output.isBlank()) return "git could not clone it";
        List<String> lines = output.strip().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        String last = lines.isEmpty() ? "git could not clone it" : lines.getLast();
        return last.length() <= 300 ? last : last.substring(0, 300) + "…";
    }
}
