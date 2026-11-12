package com.ai.orange.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.routing.RoutingProperties.Risk;
import com.ai.orange.worktree.Git;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiskPathsTest {

    @TempDir
    Path repo;

    RiskPaths riskPaths;
    String readme;
    String migration;

    @BeforeEach
    void setUp() throws Exception {
        RoutingProperties d = RoutingTableTest.defaults(RoutingTableTest.defaultRules());
        RoutingProperties props = new RoutingProperties(d.tierOrder(), d.tiers(), d.rules(),
                new Risk("large", Effort.HIGH, List.of("**/db/migration/**")), d.escalation(), d.smallModel());
        riskPaths = new RiskPaths(new RoutingTable(props));
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "test");
        readme = commit("README.md", "hello\n");
        migration = commit("src/main/resources/db/migration/V1__x.sql", "select 1;\n");
    }

    @Test
    void aDiffIntoARiskPathTouchesIt() {
        assertThat(riskPaths.touches(repo, readme, migration)).isTrue();
    }

    @Test
    void aDiffOutsideTheRiskPathsDoesNot() throws Exception {
        String later = commit("README.md", "hello again\n");
        assertThat(riskPaths.touches(repo, migration, later)).isFalse();
    }

    @Test
    void aDiffThatCannotBeReadCountsAsRisky() {
        assertThat(riskPaths.touches(repo, readme, "0123456789abcdef0123456789abcdef01234567")).isTrue();
        assertThat(riskPaths.touches(repo, "", migration)).isTrue();
        assertThat(riskPaths.touches(repo.resolve("missing"), readme, migration)).isTrue();
    }

    private String commit(String path, String content) throws Exception {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git("add", path);
        git("commit", "-q", "-m", "change " + path);
        return git("rev-parse", "HEAD").strip();
    }

    private String git(String... args) {
        return Git.run(repo, Git.QUICK, args);
    }
}
