package com.ai.orange.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.OnTrack;
import com.ai.orange.status.StatusReport.Pause;
import com.ai.orange.status.StatusReport.PlanLine;
import com.ai.orange.status.StatusReport.ProjectStatus;
import com.ai.orange.status.StatusReport.TaskLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusTextTest {

    static ProjectStatus project(String name, int running, int openAttention, Milestone milestone) {
        Map<String, Integer> byState = new LinkedHashMap<>();
        if (running > 0) byState.put("running", running);
        List<TaskLine> tasks = new ArrayList<>();
        for (int i = 0; i < running; i++) tasks.add(new TaskLine("0000000" + i, "Task " + i, "running", null));
        return new ProjectStatus("id-" + name, name, List.of(), byState, tasks, 0,
                milestone == null ? List.of() : List.of(milestone), openAttention);
    }

    static Milestone behind(String text) {
        return new Milestone("m", text, "2026-12-01T23:59Z", 5, 50, false, 0, Map.of("merged", 1, "stuck", 1),
                OnTrack.BEHIND);
    }

    @Test
    void startContextStaysShort() {
        List<ProjectStatus> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) many.add(project("project-" + i, 2, 1, behind("beta " + i)));
        StatusReport report = new StatusReport("2026-11-16T10:00Z", new Pause("user: lunch", null, "2026-11-16T09:00Z"),
                many, 15, 3, 30, List.of(new TaskLine("abcdef12", "Loose", "needs_human", null)));

        String context = StatusText.startContext(report);

        List<String> lines = context.lines().toList();
        assertThat(lines).hasSizeLessThanOrEqualTo(8);
        assertThat(lines.getFirst()).contains("paused").contains("user: lunch");
        assertThat(context).contains("project-0: 2 running, 1 needs you, milestone \"beta 0\" behind");
        assertThat(context).contains("more");
        assertThat(context).contains("15 items").contains("3 urgent");
        assertThat(lines.getLast()).isEqualTo("Ask \"what's the status\" or hand over work: \"build X in <project>\".");
    }

    @Test
    void startContextSaysNothingWaitsWhenNothingDoes() {
        StatusReport report = new StatusReport("2026-11-16T10:00Z", null, List.of(project("quiet", 0, 0, null)), 0, 0, 0,
                List.of());

        String context = StatusText.startContext(report);

        assertThat(context).doesNotContain("paused");
        assertThat(context).contains("quiet: nothing in flight");
        assertThat(context.toLowerCase()).contains("nothing waits for you");
        assertThat(context.lines().count()).isEqualTo(3);
    }

    @Test
    void startContextWithoutProjectsSaysHowToStartOne() {
        String context = StatusText.startContext(new StatusReport("2026-11-16T10:00Z", null, List.of(), 0, 0, 0, List.of()));

        assertThat(context).contains("No active project");
        assertThat(context.lines().count()).isLessThanOrEqualTo(8);
    }

    @Test
    void namesWithLineBreaksStayOnOneLine() {
        StatusReport report = new StatusReport("2026-11-16T10:00Z", null,
                List.of(project("evil\nname", 1, 0, behind("two\nlines"))), 0, 0, 1, List.of());

        String context = StatusText.startContext(report);

        assertThat(context.lines().count()).isEqualTo(3);
        assertThat(context).contains("evil name: 1 running, milestone \"two lines\" behind");
    }

    @Test
    void theFullTextHasEveryProjectsPlansTasksAndMilestones() {
        ProjectStatus p = new ProjectStatus("id", "sandbox",
                List.of(new PlanLine("p1", "asking", "Rewrite storage", "it touches the migrations", "2026-11-16T09:00Z")),
                Map.of("needs_human", 1), List.of(new TaskLine("abcdef12", "Build auth", "needs_human", "Beta")), 2,
                List.of(behind("Beta")), 1);
        StatusReport report = new StatusReport("2026-11-16T10:00Z", null, List.of(p), 1, 1, 0,
                List.of(new TaskLine("12345678", "Loose", "running", null)));

        String text = StatusText.text(report);

        assertThat(text).contains("sandbox").contains("Rewrite storage").contains("it touches the migrations")
                .contains("abcdef12").contains("Build auth").contains("Beta").contains("behind")
                .contains("2 integrated today").contains("12345678").contains("No project");
    }
}
