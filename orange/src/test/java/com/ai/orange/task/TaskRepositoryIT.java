package com.ai.orange.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.planner.Plan;
import com.ai.orange.planner.PlanRepository;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.TaskPlacement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskRepositoryIT {

    @Autowired TaskRepository tasks;
    @Autowired PlanRepository plans;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;

    @Test
    void insertKeepsPlanAndMilestone() {
        Project p = projects.create("tasks-" + UUID.randomUUID().toString().substring(0, 8), null);
        SpaceItem milestone = space.add(p.id(), SpaceItem.MILESTONE, "beta", SpaceService.Author.USER, null, null,
                null);
        Plan plan = plans.insert(p.id(), "a request", null, null, null);

        Task t = tasks.insert(new TaskDef("k", "Planned", null, null, null, null, new TaskPlacement(p.id(), null),
                plan.id(), milestone.id()));

        assertThat(t.planId()).isEqualTo(plan.id());
        assertThat(t.milestoneId()).isEqualTo(milestone.id());
        Task read = tasks.findById(t.id()).orElseThrow();
        assertThat(read.planId()).isEqualTo(plan.id());
        assertThat(read.milestoneId()).isEqualTo(milestone.id());

        Task plain = tasks.insert(new TaskDef("p", "Unplanned", null, null, null, null));
        assertThat(plain.planId()).isNull();
        assertThat(plain.milestoneId()).isNull();
    }

    @Test
    void findByIdPrefixNeedsEightCharactersAndFindsOne() {
        Task t = tasks.insert(new TaskDef("k", "Prefixed", null, null, null, null));
        String id = t.id().toString();

        // uuidv7 starts with the time, so tasks made together share a short prefix; a long one is unique.
        assertThat(tasks.findByIdPrefix(id.substring(0, 30))).extracting(Task::id).containsExactly(t.id());
        assertThat(tasks.findByIdPrefix(id.toUpperCase())).extracting(Task::id).containsExactly(t.id());
        assertThat(tasks.findByIdPrefix("  " + id + " ")).extracting(Task::id).containsExactly(t.id());
        List<Task> short8 = tasks.findByIdPrefix(id.substring(0, 8));
        assertThat(short8).hasSizeBetween(1, 2);

        assertThatThrownBy(() -> tasks.findByIdPrefix(id.substring(0, 7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tasks.findByIdPrefix("zzzzzzzz")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tasks.findByIdPrefix("0000000%")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tasks.findByIdPrefix(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.findByIdPrefix("ffffffff-ffff")).isEmpty();
    }
}
