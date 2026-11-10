package com.ai.orange.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.run.RunKind;
import org.junit.jupiter.api.Test;

/** One assertion per row of the plan's tools-per-kind table (v2 §8, project-space decision 13). */
class ToolPolicyTest {

    @Test
    void implementAndFixRunsEditTheWorktreeAndReportThroughMcp() {
        for (RunKind kind : new RunKind[]{RunKind.IMPLEMENT, RunKind.CI_FIX}) {
            ToolPolicy p = ToolPolicy.forKind(kind);
            assertThat(p.tools()).containsExactly("Read", "Write", "Edit", "Bash", "Glob", "Grep", "TodoWrite");
            assertThat(p.allowedTools()).containsExactly("Read", "Write", "Edit", "Bash", "Glob", "Grep", "TodoWrite",
                    "mcp__orange__report_result", "mcp__orange__request_human");
            assertThat(p.permissionMode()).isEqualTo("acceptEdits");
            assertThat(p.mcp()).isTrue();
        }
    }

    @Test
    void diffReviewReadsOnlyAndHasNoBash() {
        ToolPolicy p = ToolPolicy.forKind(RunKind.DIFF_REVIEW);
        assertThat(p.tools()).containsExactly("Read", "Glob", "Grep");
        assertThat(p.allowedTools()).containsExactly("Read", "Glob", "Grep", "mcp__orange__submit_review");
        assertThat(p.permissionMode()).isEqualTo("dontAsk");
        assertThat(p.mcp()).isTrue();
    }

    @Test
    void briefHasNoBuiltInToolsAndOneMcpTool() {
        ToolPolicy p = ToolPolicy.forKind(RunKind.BRIEF);
        assertThat(p.tools()).isEmpty();
        assertThat(p.allowedTools()).containsExactly("mcp__orange__submit_brief");
        assertThat(p.permissionMode()).isEqualTo("dontAsk");
        assertThat(p.mcp()).isTrue();
        assertThat(ToolPolicy.forRun(RunKind.BRIEF, false)).isEqualTo(p);
    }

    @Test
    void inAContainerImplementAndFixRunsBypassPermissions() {
        for (RunKind kind : new RunKind[]{RunKind.IMPLEMENT, RunKind.CI_FIX}) {
            ToolPolicy host = ToolPolicy.forKind(kind);
            ToolPolicy p = host.inContainer();
            assertThat(p.permissionMode()).isEqualTo("bypassPermissions");
            // Nothing else changes: the container is the boundary, the tools are the same.
            assertThat(p.tools()).isEqualTo(host.tools());
            assertThat(p.allowedTools()).isEqualTo(host.allowedTools());
            assertThat(p.mcp()).isEqualTo(host.mcp());
        }
    }

    @Test
    void inAContainerARunWithoutBashKeepsItsMode() {
        ToolPolicy review = ToolPolicy.forKind(RunKind.DIFF_REVIEW);
        assertThat(review.inContainer()).isSameAs(review);
        assertThat(review.inContainer().permissionMode()).isEqualTo("dontAsk");
        // Runs that never go into a container today would keep theirs too.
        for (RunKind kind : new RunKind[]{RunKind.PLAN, RunKind.JUDGE, RunKind.BRIEF}) {
            ToolPolicy p = ToolPolicy.forKind(kind);
            assertThat(p.inContainer()).as(kind.dbValue()).isSameAs(p);
        }
    }

    @Test
    void verifyAndIntegrateAreNotAgentRuns() {
        for (RunKind kind : new RunKind[]{RunKind.VERIFY, RunKind.INTEGRATE}) {
            assertThatThrownBy(() -> ToolPolicy.forKind(kind)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void planKeepsItsV1ToolsUntilSubmitPlanArrives() {
        ToolPolicy p = ToolPolicy.forKind(RunKind.PLAN);
        assertThat(p.tools()).containsExactly("Read", "Write", "Glob", "Grep", "WebFetch");
        assertThat(p.allowedTools()).containsExactly("Read", "Write", "Glob", "Grep", "WebFetch");
        assertThat(p.permissionMode()).isEqualTo("acceptEdits");
        assertThat(p.mcp()).isFalse();
    }
}
