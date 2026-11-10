package com.ai.orange.project;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.session.SessionRepository;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects and their repos. A session's repo decides its project (spec decision 9); a repo orange
 * has not seen becomes a {@code proposed} project and a question for the user.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projects;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final SessionRepository sessions;

    public ProjectService(ProjectRepository projects, AttentionService attention, AttentionRepository attentionItems,
                          SessionRepository sessions) {
        this.sessions = sessions;
        this.projects = projects;
        this.attention = attention;
        this.attentionItems = attentionItems;
    }

    static String proposedKey(UUID projectId) {
        return "project:" + projectId + ":proposed";
    }

    /**
     * The project of a repo on a node, created as {@code proposed} when the repo is new.
     * {@code repoRoot} is the main checkout, also for sessions in one of its worktrees.
     */
    @Transactional
    public Project resolve(String nodeId, String repoRoot, String remoteUrl, String defaultBranch) {
        Optional<ProjectRepo> known = projects.findRepo(nodeId, repoRoot);
        if (known.isPresent()) return projects.findById(known.get().projectId()).orElseThrow();

        Project project = projects.insert(freeName(nameOf(repoRoot)), Project.PROPOSED, null);
        projects.putRepo(project.id(), nodeId, repoRoot, remoteUrl, defaultBranch);
        log.info("new repo {} on node {}: proposed project {} ({})", repoRoot, nodeId, project.name(), project.id());
        attention.raise(AttentionItem.PROJECT_PROPOSED, proposedKey(project.id()), AttentionItem.NORMAL, project.id(),
                null, "New project: " + project.name(),
                "A session is working in " + repoRoot + ", a repo orange has not seen before.\n"
                        + "Answer \"confirm\" to track it as project \"" + project.name() + "\", or \"ignore\" to "
                        + "archive it so its sessions raise nothing. It is watched like any project until then.");
        return project;
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
