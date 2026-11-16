package com.ai.orange.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** A plan is a row: the request, what the planner made of it and where it went (R4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlanRepositoryIT {

    @Autowired PlanRepository plans;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired TransactionTemplate tx;

    private Project project() {
        return projects.create("plans-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    @Test
    void insertsAPlanningPlanAndFinishesIt() {
        Project p = project();
        SpaceItem milestone = space.add(p.id(), SpaceItem.MILESTONE, "beta", SpaceService.Author.USER,
                OffsetDateTime.now().plusDays(10), null, null);

        Plan plan = plans.insert(p.id(), "build the login page", null, null, null);
        assertThat(plan.status()).isEqualTo(Plan.PLANNING);
        assertThat(plan.projectId()).isEqualTo(p.id());
        assertThat(plan.request()).isEqualTo("build the login page");
        assertThat(plan.createdAt()).isNotNull();

        plans.finishPlanning(plan.id(), Plan.ASKING, true, "the request is ambiguous",
                JSONB.valueOf("{\"tasks\": []}"), "one page", null, null, milestone.id());

        Plan done = plans.findById(plan.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(Plan.ASKING);
        assertThat(done.askFirst()).isTrue();
        assertThat(done.reason()).isEqualTo("the request is ambiguous");
        assertThat(done.plan().data()).contains("tasks");
        assertThat(done.architecture()).isEqualTo("one page");
        assertThat(done.milestoneId()).isEqualTo(milestone.id());

        Plan revision = plans.insert(p.id(), "build the login page", "with a password reset", plan.id(), null);
        assertThat(revision.revisesId()).isEqualTo(plan.id());
        assertThat(revision.note()).isEqualTo("with a password reset");
        assertThat(plans.findRecent(p.id(), 10)).extracting(Plan::id).containsExactly(revision.id(), plan.id());
    }

    @Test
    void moveOnlyFromTheExpectedStatus() {
        Plan plan = plans.insert(project().id(), "a request", null, null, null);

        assertThat(plans.move(plan.id(), Plan.ASKING, Plan.DISPATCHED)).isFalse();
        assertThat(plans.move(plan.id(), Plan.PLANNING, Plan.ASKING)).isTrue();
        assertThat(plans.move(plan.id(), Plan.PLANNING, Plan.ASKING)).isFalse();

        UUID item = UUID.randomUUID();
        plans.setAttention(plan.id(), item);
        Plan locked = tx.execute(s -> plans.lock(plan.id()).orElseThrow());
        assertThat(locked.status()).isEqualTo(Plan.ASKING);
        assertThat(locked.attentionId()).isEqualTo(item);
    }

    @Test
    void findByStatusSeesPlanningAndAsking() {
        UUID projectId = project().id();
        Plan planning = plans.insert(projectId, "one", null, null, null);
        Plan asking = plans.insert(projectId, "two", null, null, null);
        Plan dropped = plans.insert(projectId, "three", null, null, null);
        plans.move(asking.id(), Plan.PLANNING, Plan.ASKING);
        plans.move(dropped.id(), Plan.PLANNING, Plan.DROPPED);

        List<UUID> open = plans.findByStatus(List.of(Plan.PLANNING, Plan.ASKING)).stream().map(Plan::id).toList();
        assertThat(open).contains(planning.id(), asking.id()).doesNotContain(dropped.id());
    }
}
