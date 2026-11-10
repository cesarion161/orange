package com.ai.orange.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.json.Json;
import com.ai.orange.project.SpaceService.Author;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The project space: items supersede instead of changing, agents only propose, conventions resolve in order. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpaceIT {

    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired Conventions conventions;
    @Autowired SessionRepository sessions;

    Project project() {
        return projects.create("space-" + UUID.randomUUID(), null);
    }

    static JsonNode json(String raw) throws Exception {
        return Json.mapper().readTree(raw);
    }

    @Test
    void aChangeSupersedesAndKeepsTheOldItem() {
        Project p = project();
        SpaceItem first = space.add(p.id(), SpaceItem.GOAL, "Ship v2", Author.USER, null, null, null);
        SpaceItem second = space.add(p.id(), SpaceItem.GOAL, "Ship v2 by spring", Author.USER, null, null, first.id());

        assertThat(second.supersedesId()).isEqualTo(first.id());
        assertThat(space.find(first.id()).orElseThrow().status()).isEqualTo(SpaceItem.SUPERSEDED);
        assertThat(space.current(p.id())).extracting(SpaceItem::id).containsExactly(second.id());
        assertThatThrownBy(() -> space.add(p.id(), SpaceItem.GOAL, "again", Author.USER, null, null, first.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("superseded");
    }

    @Test
    void anAgentOnlyProposesAndAcceptingMakesItActive() {
        Project p = project();
        SpaceItem active = space.add(p.id(), SpaceItem.DECISION, "Use Postgres", Author.USER, null, null, null);
        SpaceItem proposal = space.add(p.id(), SpaceItem.DECISION, "Use Postgres 18", Author.agent("run-1"), null, null,
                active.id());

        assertThat(proposal.status()).isEqualTo(SpaceItem.PROPOSED);
        assertThat(proposal.authorKind()).isEqualTo("agent");
        // A proposal changes nothing until it is accepted.
        assertThat(space.find(active.id()).orElseThrow().status()).isEqualTo(SpaceItem.ACTIVE);

        assertThat(space.accept(proposal.id()).status()).isEqualTo(SpaceItem.ACTIVE);
        assertThat(space.find(active.id()).orElseThrow().status()).isEqualTo(SpaceItem.SUPERSEDED);
        assertThatThrownBy(() -> space.accept(proposal.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProposalCanBeDroppedAndAnActiveItemDone() {
        Project p = project();
        SpaceItem proposal = space.add(p.id(), SpaceItem.NOTE, "maybe", Author.agent("s"), null, null, null);
        assertThat(space.drop(proposal.id()).status()).isEqualTo(SpaceItem.DROPPED);
        assertThatThrownBy(() -> space.done(proposal.id())).isInstanceOf(IllegalArgumentException.class);

        SpaceItem milestone = space.add(p.id(), SpaceItem.MILESTONE, "Beta", Author.USER,
                OffsetDateTime.now().plusDays(30), null, null);
        assertThat(milestone.dueAt()).isNotNull();
        assertThat(space.done(milestone.id()).status()).isEqualTo(SpaceItem.DONE);
        assertThat(space.current(p.id())).isEmpty();
    }

    @Test
    void nothingIsMandatoryButWhatIsGivenMustMakeSense() {
        Project p = project();
        assertThat(space.add(p.id(), SpaceItem.MILESTONE, "No date", Author.USER, null, null, null).dueAt()).isNull();
        assertThatThrownBy(() -> space.add(p.id(), SpaceItem.GOAL, "dated goal", Author.USER, OffsetDateTime.now(), null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> space.add(p.id(), SpaceItem.GOAL, "  ", Author.USER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> space.add(p.id(), SpaceItem.CONVENTION, "x", Author.USER, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conventionsResolveSessionThenProjectThenGlobal() throws Exception {
        Project p = project();
        Session s = sessions.insert("claude-code", "space-" + UUID.randomUUID(), "local");

        // global default
        assertThat(conventions.resolve("integration", p.id(), s.id())).contains(json("\"merge\""));
        assertThat(conventions.resolve("done", null, null)).contains(json("[\"evidence\",\"integrated\"]"));
        assertThat(conventions.resolve("cadence", p.id(), s.id())).isEmpty();

        space.setConvention(p.id(), null, "integration", json("\"pr\""), Author.USER);
        assertThat(conventions.resolve("integration", p.id(), s.id())).contains(json("\"pr\""));

        space.setConvention(p.id(), s.id(), "integration", json("\"branch\""), Author.USER);
        assertThat(conventions.resolve("integration", p.id(), s.id())).contains(json("\"branch\""));
        assertThat(conventions.resolve("integration", p.id(), null)).contains(json("\"pr\""));

        // another project is untouched
        assertThat(conventions.resolve("integration", project().id(), null)).contains(json("\"merge\""));
    }

    @Test
    void settingAConventionAgainSupersedesItsValue() throws Exception {
        Project p = project();
        SpaceItem first = space.setConvention(p.id(), null, "evidence", json("[\"./gradlew test\"]"), Author.USER);
        SpaceItem second = space.setConvention(p.id(), null, "evidence", json("[\"./gradlew build\"]"), Author.USER);

        assertThat(second.supersedesId()).isEqualTo(first.id());
        assertThat(space.find(first.id()).orElseThrow().status()).isEqualTo(SpaceItem.SUPERSEDED);
        assertThat(conventions.resolve("evidence", p.id(), null)).contains(json("[\"./gradlew build\"]"));
    }

    @Test
    void anAgentsConventionTakesEffectOnlyWhenAccepted() throws Exception {
        Project p = project();
        space.setConvention(p.id(), null, "evidence", json("[\"make test\"]"), Author.USER);
        SpaceItem proposal = space.setConvention(p.id(), null, "evidence", json("[\"true\"]"), Author.agent("run-9"));

        assertThat(proposal.status()).isEqualTo(SpaceItem.PROPOSED);
        assertThat(conventions.resolve("evidence", p.id(), null)).contains(json("[\"make test\"]"));

        space.accept(proposal.id());
        assertThat(conventions.resolve("evidence", p.id(), null)).contains(json("[\"true\"]"));
    }

    @Test
    void quietHoursAreGlobalOnly() throws Exception {
        Project p = project();
        space.setConvention(p.id(), null, Conventions.QUIET_HOURS, json("[\"01:00\",\"02:00\"]"), Author.USER);
        assertThat(conventions.resolve(Conventions.QUIET_HOURS, p.id(), null)).contains(json("[\"22:00\",\"08:00\"]"));
    }

    @Test
    void projectNamesAreUniqueAndStatusesChecked() {
        Project p = project();
        assertThatThrownBy(() -> projects.create(p.name(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.update(p.id(), null, "finished", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(projects.update(p.id(), null, Project.PAUSED, "On hold").status()).isEqualTo(Project.PAUSED);
        assertThat(projects.find(p.name()).orElseThrow().summary()).isEqualTo("On hold");
        assertThat(projects.find(p.id().toString())).isPresent();
    }
}
