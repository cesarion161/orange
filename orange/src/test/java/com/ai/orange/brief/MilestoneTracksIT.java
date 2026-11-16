package com.ai.orange.brief;

import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.SpaceService.Author;
import com.ai.orange.project.TaskPlacement;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A milestone's tasks (remote-first spec §7.6): counted by state over the milestone's supersede chain, so
 * editing a milestone keeps its tasks and dropping one leaves them counting nowhere; a dated milestone
 * with tasks is labelled on track or behind.
 */
@SpringBootTest(properties = "orange.test.context=brief-snapshot")
@Import(TestcontainersConfiguration.class)
class MilestoneTracksIT {

    @Autowired MilestoneTracks tracks;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    final ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    final OffsetDateTime at = now.toOffsetDateTime();

    Project project() {
        return projects.create("tracks-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    /** A milestone due in 5 days, half of its time gone. */
    SpaceItem milestone(Project p, String text, UUID supersedes) {
        SpaceItem m = space.add(p.id(), SpaceItem.MILESTONE, text, Author.USER, at.plusDays(5), null, supersedes);
        dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.CREATED_AT, at.minusDays(5)).where(SPACE_ITEMS.ID.eq(m.id())).execute();
        return m;
    }

    Task task(Project p, UUID milestoneId, String status) {
        Task t = taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), "A task", null, null, null,
                null, new TaskPlacement(p.id(), null), null, milestoneId)), List.of()).get(0);
        dsl.update(TASKS).set(TASKS.STATUS, status).where(TASKS.ID.eq(t.id())).execute();
        return t;
    }

    List<Milestone> read(Project p) {
        return tracks.read(List.of(p.id()), at, now.toLocalDate(), ZoneOffset.UTC, 0.2).getOrDefault(p.id(), List.of());
    }

    @Test
    void countsTasksByState() {
        Project p = project();
        SpaceItem beta = milestone(p, "Beta", null);
        task(p, beta.id(), "integrated");
        task(p, beta.id(), "integrated");
        task(p, beta.id(), "running");
        task(p, beta.id(), "verified");
        task(p, beta.id(), "ready");
        task(p, beta.id(), "cancelled");
        task(p, null, "running");

        List<Milestone> ms = read(p);

        assertThat(ms).hasSize(1);
        Milestone m = ms.getFirst();
        assertThat(m.id()).isEqualTo(beta.id().toString());
        assertThat(m.text()).isEqualTo("Beta");
        assertThat(m.elapsedPercent()).isEqualTo(50);
        assertThat(m.tasks()).containsExactly(entry(MilestoneTracks.MERGED, 2), entry(MilestoneTracks.RUNNING, 2),
                entry(MilestoneTracks.NOT_STARTED, 1));
        // 2 of 5 merged = 40% at 50% of the time gone: within the margin.
        assertThat(m.track()).isEqualTo(OnTrack.ON_TRACK);

        task(p, beta.id(), "needs_human");
        Milestone stuck = read(p).getFirst();
        assertThat(stuck.tasks()).containsEntry(MilestoneTracks.STUCK, 1);
        assertThat(stuck.track()).isEqualTo(OnTrack.BEHIND);
    }

    @Test
    void anUndatedMilestoneShowsItsTasksWithNoLabel() {
        Project p = project();
        SpaceItem someday = space.add(p.id(), SpaceItem.MILESTONE, "Someday", Author.USER, null, null, null);
        task(p, someday.id(), "needs_human");

        Milestone m = read(p).getFirst();

        assertThat(m.tasks()).containsExactly(entry(MilestoneTracks.STUCK, 1));
        assertThat(m.track()).isNull();
        assertThat(m.elapsedPercent()).isNull();
    }

    @Test
    void aDatedMilestoneWithoutTasksHasNoLabel() {
        Project p = project();
        milestone(p, "Beta", null);

        Milestone m = read(p).getFirst();

        assertThat(m.tasks()).isEmpty();
        assertThat(m.track()).isNull();
    }

    @Test
    void tasksFollowTheSupersedeChain() {
        Project p = project();
        SpaceItem first = milestone(p, "Beta", null);
        task(p, first.id(), "integrated");
        SpaceItem second = milestone(p, "Public beta", first.id());
        task(p, second.id(), "running");
        SpaceItem third = milestone(p, "Public beta, with docs", second.id());
        task(p, third.id(), "ready");

        List<Milestone> ms = read(p);

        assertThat(ms).hasSize(1);
        Milestone m = ms.getFirst();
        assertThat(m.id()).isEqualTo(third.id().toString());
        assertThat(m.text()).isEqualTo("Public beta, with docs");
        assertThat(m.tasks()).containsExactly(entry(MilestoneTracks.MERGED, 1), entry(MilestoneTracks.RUNNING, 1),
                entry(MilestoneTracks.NOT_STARTED, 1));
        assertThat(tracks.current(first.id())).contains(third.id());
        assertThat(tracks.current(second.id())).contains(third.id());
        assertThat(tracks.current(third.id())).contains(third.id());
    }

    @Test
    void anAgentsProposedEditDoesNotTakeTheTasks() {
        Project p = project();
        SpaceItem beta = milestone(p, "Beta", null);
        task(p, beta.id(), "running");
        SpaceItem proposal = space.add(p.id(), SpaceItem.MILESTONE, "Beta, later", Author.agent("run"), null, null,
                beta.id());

        assertThat(read(p)).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(beta.id().toString());
            assertThat(m.tasks()).containsExactly(entry(MilestoneTracks.RUNNING, 1));
        });
        assertThat(tracks.current(beta.id())).contains(beta.id());
        assertThat(tracks.current(proposal.id())).isEmpty();
    }

    @Test
    void aDroppedMilestonesTasksCountNowhere() {
        Project p = project();
        SpaceItem first = milestone(p, "Beta", null);
        task(p, first.id(), "running");
        SpaceItem second = milestone(p, "Public beta", first.id());
        task(p, second.id(), "ready");
        SpaceItem other = milestone(p, "Launch", null);
        task(p, other.id(), "integrated");

        space.drop(second.id());

        List<Milestone> ms = read(p);
        assertThat(ms).extracting(Milestone::text).containsExactly("Launch");
        assertThat(ms.getFirst().tasks()).containsExactly(entry(MilestoneTracks.MERGED, 1));
        assertThat(tracks.current(first.id())).isEmpty();
        assertThat(tracks.current(second.id())).isEmpty();
    }

    @Test
    void projectsAreReadTogetherAndEachKeepsItsOwn() {
        Project a = project();
        Project b = project();
        SpaceItem ma = milestone(a, "A1", null);
        milestone(b, "B1", null);
        task(a, ma.id(), "integrated");

        Map<UUID, List<Milestone>> all = tracks.read(List.of(a.id(), b.id()), at, now.toLocalDate(), ZoneOffset.UTC, 0.2);

        assertThat(all.get(a.id())).extracting(Milestone::text).containsExactly("A1");
        assertThat(all.get(b.id())).singleElement().satisfies(m -> assertThat(m.tasks()).isEmpty());
        assertThat(tracks.read(List.of(), at, now.toLocalDate(), ZoneOffset.UTC, 0.2)).isEmpty();
        dsl.deleteFrom(PROJECTS).where(PROJECTS.ID.in(a.id(), b.id())).execute();
    }
}
