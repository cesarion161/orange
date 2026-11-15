package com.ai.orange.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.repo.RepoRepository;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Projects own GitHub repos (remote-first spec §7.1): a repo is its remote, whatever checkout reports it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProjectReposIT {

    @Autowired ProjectService projects;
    @Autowired ProjectRepository projectRepository;
    @Autowired RepoRepository repos;
    @Autowired AttentionRepository attention;
    @Autowired SessionRepository sessions;

    static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void twoCheckoutsOfOneRepoAreOneProject() {
        String name = "thing-" + unique();
        Project first = projects.resolve("laptop", "/a/" + name, "git@github.com:Me/" + name + ".git", "main");
        Project second = projects.resolve("vps", "/srv/" + name, "https://github.com/me/" + name, null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(attention.findByKey(ProjectService.proposedKey(first.id()))).isPresent();
        Repo repo = repos.findByKey(RepoKey.of("me/" + name)).orElseThrow();
        assertThat(repo.projectId()).isEqualTo(first.id());
        assertThat(repo.defaultBranch()).isEqualTo("main");
        assertThat(projects.repoOfCheckout("vps", "/srv/" + name)).map(Repo::id).contains(repo.id());
        assertThat(projects.repoOfCheckout("laptop", "/a/" + name)).map(Repo::id).contains(repo.id());
    }

    @Test
    void aRepoWithoutAGithubRemoteHasNoRepoRow() {
        Project p = projects.resolve("n", "/b/" + unique(), "https://gitlab.com/o/n.git", null);
        assertThat(p.status()).isEqualTo(Project.PROPOSED);
        assertThat(repos.ofProject(p.id())).isEmpty();
    }

    @Test
    void aKnownCheckoutIsLinkedWhenItsRemoteAppears() {
        String root = "/c/" + unique();
        Project p = projects.resolve("n", root, null, null);
        assertThat(projects.repoOfCheckout("n", root)).isEmpty();

        String name = "late-" + unique();
        Project again = projects.resolve("n", root, "https://github.com/me/" + name + ".git", "trunk");
        assertThat(again.id()).isEqualTo(p.id());
        Repo repo = projects.repoOfCheckout("n", root).orElseThrow();
        assertThat(repo.key()).isEqualTo(RepoKey.of("me/" + name));
        assertThat(repo.projectId()).isEqualTo(p.id());
        assertThat(repo.defaultBranch()).isEqualTo("trunk");
    }

    @Test
    void addingAGithubRepoMovesItsCheckoutsAndSessions() {
        String name = "moved-" + unique();
        String root = "/d/" + name;
        Project p1 = projects.resolve("n", root, "https://github.com/me/" + name + ".git", null);
        Session s = sessions.insert("claude-code", "repos-" + unique(), "n");
        sessions.save(new Session(s.id(), p1.id(), s.harness(), s.externalId(), "n", root, root, "main", root, null,
                s.status(), null, null, null, 0, s.lastEventAt(), null, null, s.createdAt(), null, null, null));
        Project p2 = projects.create("target-" + unique(), null);

        Repo repo = projects.addGithubRepo(p2.id(), RepoKey.of("me/" + name));

        assertThat(repo.projectId()).isEqualTo(p2.id());
        assertThat(repos.findByKey(RepoKey.of("me/" + name)).orElseThrow().projectId()).isEqualTo(p2.id());
        assertThat(projectRepository.findRepo("n", root).orElseThrow().projectId()).isEqualTo(p2.id());
        assertThat(sessions.findById(s.id()).orElseThrow().projectId()).isEqualTo(p2.id());
        // Attaching it again is a no-op, not an error.
        assertThat(projects.addGithubRepo(p2.id(), RepoKey.of("me/" + name)).id()).isEqualTo(repo.id());
    }

    @Test
    void placementNeedsOneRepoOrAName() {
        Project p = projects.create("place-" + unique(), null);
        assertThatThrownBy(() -> projects.placeTask(p.name(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has no GitHub repo");

        Repo a = projects.addGithubRepo(p.id(), RepoKey.of("me/a-" + unique()));
        assertThat(projects.placeTask(p.name(), null)).isEqualTo(new TaskPlacement(p.id(), a.id()));
        assertThat(projects.placeTask(p.id().toString(), " ")).isEqualTo(new TaskPlacement(p.id(), a.id()));

        Repo b = projects.addGithubRepo(p.id(), RepoKey.of("me/b-" + unique()));
        assertThatThrownBy(() -> projects.placeTask(p.name(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has 2 repos");
        assertThat(projects.placeTask(p.name(), b.key().toString().toUpperCase()))
                .isEqualTo(new TaskPlacement(p.id(), b.id()));
        assertThatThrownBy(() -> projects.placeTask(p.name(), "me/elsewhere"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not a repo of");

        assertThat(projects.placeTask(null, null)).isEqualTo(TaskPlacement.NONE);
        assertThat(projects.placeTask(" ", null)).isEqualTo(TaskPlacement.NONE);
        assertThatThrownBy(() -> projects.placeTask(null, "me/x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
        assertThatThrownBy(() -> projects.placeTask("nope-" + unique(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such project");
    }
}
