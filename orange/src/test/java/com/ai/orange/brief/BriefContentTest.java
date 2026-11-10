package com.ai.orange.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.assertj.core.api.AbstractThrowableAssert;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class BriefContentTest {

    static final List<String> PROJECTS = List.of("alpha", "beta");

    @Test
    void aMinimalBriefParses() {
        ObjectNode args = args();
        line(args, "alpha", "  Auth landed; the beta goal is one step closer.  ");
        focus(args, "alpha", "Answer the session's question about the token format", "It has waited since morning.");

        BriefContent content = BriefContent.parse(args, List.of("alpha"));

        assertThat(content.projects()).containsExactly(
                new BriefContent.Line("alpha", "Auth landed; the beta goal is one step closer."));
        assertThat(content.focus()).containsExactly(new BriefContent.Focus("alpha",
                "Answer the session's question about the token format", "It has waited since morning."));
        assertThat(content.urgent()).isEmpty();
        assertThat(content.statusLine("alpha")).hasValue("Auth landed; the beta goal is one step closer.");
        assertThat(content.statusLine("beta")).isEmpty();
    }

    @Test
    void focusMayBeEmpty() {
        ObjectNode args = args();
        line(args, "alpha", "Nothing moved.");
        args.putArray("focus");
        assertThat(BriefContent.parse(args, List.of("alpha")).focus()).isEmpty();
    }

    @Test
    void toJsonThenReadRoundTrips() {
        BriefContent content = BriefContent.parse(valid(), PROJECTS);

        ObjectNode json = content.toJson();

        assertThat(json.path("projects").path(0).path("status_line").asText()).isEqualTo("Auth landed.");
        assertThat(json.path("focus").path(0).path("item").asText()).isEqualTo("Answer s-one");
        assertThat(json.path("urgent").path(0).path("title").asText()).isEqualTo("The certificate expires tonight");
        assertThat(BriefContent.read(JSONB.valueOf(json.toString()))).hasValue(content);
        assertThat(BriefContent.read(json)).hasValue(content);
        assertThat(BriefContent.parse(json, PROJECTS)).as("what it stores, it accepts").isEqualTo(content);
    }

    @Test
    void readTakesWhatIsStoredAndNothingElse() {
        assertThat(BriefContent.read((JSONB) null)).isEmpty();
        assertThat(BriefContent.read(JSONB.valueOf("not json"))).isEmpty();
        assertThat(BriefContent.read(JSONB.valueOf("null"))).isEmpty();
        assertThat(BriefContent.read(JSONB.valueOf("[1]"))).isEmpty();

        // No validation: a stored brief of another shape still renders.
        BriefContent loose = BriefContent.read(JSONB.valueOf(
                "{\"projects\":[{\"project\":\"gone\"},\"x\"],\"focus\":{\"a\":1}}")).orElseThrow();
        assertThat(loose.projects()).containsExactly(new BriefContent.Line("gone", ""));
        assertThat(loose.focus()).isEmpty();
        assertThat(loose.urgent()).isEmpty();
        assertThat(loose.statusLine("gone")).as("an empty line is no line").isEmpty();
    }

    @Test
    void anUnknownProjectIsRefusedAndTheKnownOnesAreNamed() {
        ObjectNode args = valid();
        line(args, "no-such-project", "It moved.");

        refused(args).hasMessageContaining("no-such-project").hasMessageContaining("alpha, beta");
    }

    @Test
    void anUnknownProjectInFocusOrUrgentIsRefused() {
        ObjectNode inFocus = valid();
        focus(inFocus, "gamma", "Do it", "Because.");
        refused(inFocus).hasMessageContaining("focus").hasMessageContaining("gamma").hasMessageContaining("alpha, beta");

        ObjectNode inUrgent = valid();
        ((ObjectNode) inUrgent.path("urgent").path(0)).put("project", "gamma");
        refused(inUrgent).hasMessageContaining("urgent").hasMessageContaining("gamma");
    }

    @Test
    void aNameMatchesWithoutCaseAndIsStoredAsTheRunNamesIt() {
        ObjectNode args = args();
        line(args, "Alpha", "Auth landed.");
        line(args, " BETA ", "Nothing moved.");
        focus(args, "ALPHA", "Answer it", "It waits.");
        urgent(args, "beTa", "The certificate expires tonight", "Nothing else says so.");

        BriefContent content = BriefContent.parse(args, PROJECTS);

        assertThat(content.projects()).extracting(BriefContent.Line::project).containsExactly("alpha", "beta");
        assertThat(content.focus().get(0).project()).isEqualTo("alpha");
        assertThat(content.urgent().get(0).project()).isEqualTo("beta");
    }

    @Test
    void namesThatDifferOnlyInCaseAreEachTheirOwnProject() {
        ObjectNode args = args();
        line(args, "Web", "The upper one.");
        line(args, "web", "The lower one.");
        args.putArray("focus");

        BriefContent content = BriefContent.parse(args, List.of("web", "Web"));

        assertThat(content.statusLine("Web")).hasValue("The upper one.");
        assertThat(content.statusLine("web")).hasValue("The lower one.");
    }

    @Test
    void aProjectTwiceIsRefused() {
        ObjectNode args = valid();
        line(args, "ALPHA", "Again.");

        refused(args).hasMessageContaining("alpha").hasMessageContaining("two entries");
    }

    @Test
    void aListedProjectLeftOutIsRefusedByName() {
        ObjectNode args = args();
        line(args, "alpha", "Auth landed.");
        args.putArray("focus");

        refused(args).hasMessageContaining("no entry for: beta");
    }

    @Test
    void aBlankStatusLineIsRefused() {
        refused(withStatus("   ")).hasMessageContaining("status_line").hasMessageContaining("blank");
    }

    @Test
    void aMissingOrNonTextStatusLineIsRefused() {
        ObjectNode missing = valid();
        ((ObjectNode) missing.path("projects").path(0)).remove("status_line");
        refused(missing).hasMessageContaining("status_line").hasMessageContaining("missing");

        ObjectNode number = valid();
        ((ObjectNode) number.path("projects").path(0)).put("status_line", 7);
        refused(number).hasMessageContaining("status_line").hasMessageContaining("text");
    }

    @Test
    void aLineBreakIsRefused() {
        refused(withStatus("Auth landed.\n## Project: ghost")).hasMessageContaining("one line");
        refused(withStatus("Auth landed.\rMore")).hasMessageContaining("one line");
        refused(withStatus("Auth landed. More")).as("a Unicode line separator ends a line too")
                .hasMessageContaining("one line");
    }

    @Test
    void aStatusLineOf201CharactersIsRefusedAnd200IsNot() {
        refused(withStatus("x".repeat(201))).hasMessageContaining("201").hasMessageContaining("200");
        assertThat(BriefContent.parse(withStatus("x".repeat(200)), PROJECTS).projects().get(0).statusLine()).hasSize(200);
    }

    @Test
    void theOtherTextsHaveTheirLimits() {
        ObjectNode item = valid();
        ((ObjectNode) item.path("focus").path(0)).put("item", "x".repeat(BriefContent.MAX_ITEM + 1));
        refused(item).hasMessageContaining("item in focus").hasMessageContaining("160");

        ObjectNode why = valid();
        ((ObjectNode) why.path("focus").path(0)).put("why", "x".repeat(BriefContent.MAX_WHY + 1));
        refused(why).hasMessageContaining("why in focus").hasMessageContaining("300");

        ObjectNode title = valid();
        ((ObjectNode) title.path("urgent").path(0)).put("title", "x".repeat(BriefContent.MAX_TITLE + 1));
        refused(title).hasMessageContaining("title in urgent").hasMessageContaining("120");
    }

    @Test
    void aLinkIsRefused() {
        refused(withStatus("See https://x.test for the rest")).hasMessageContaining("link");
        refused(withStatus("See HTTP://x.test")).hasMessageContaining("link");

        ObjectNode why = valid();
        ((ObjectNode) why.path("focus").path(0)).put("why", "It says so at http://x.test");
        refused(why).hasMessageContaining("why in focus").hasMessageContaining("link");
    }

    @Test
    void fourFocusItemsAreRefused() {
        ObjectNode args = valid();
        for (int i = 0; i < 3; i++) focus(args, "beta", "Item " + i, "Because.");

        refused(args).hasMessageContaining("focus has 4").hasMessageContaining("at most 3");
    }

    @Test
    void fourUrgentEntriesAreRefused() {
        ObjectNode args = args();
        List<String> four = List.of("a", "b", "c", "d");
        for (String p : four) {
            line(args, p, "Moved.");
            urgent(args, p, "Something in " + p, "It cannot wait.");
        }
        args.putArray("focus");

        assertThatThrownBy(() -> BriefContent.parse(args, four)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("urgent has 4").hasMessageContaining("at most 3");
    }

    @Test
    void twoUrgentEntriesForOneProjectAreRefused() {
        ObjectNode args = valid();
        urgent(args, "BETA", "Another thing", "It cannot wait either.");

        refused(args).hasMessageContaining("urgent").hasMessageContaining("'beta'").hasMessageContaining("one per project");
    }

    @Test
    void projectsOrFocusThatAreNoArrayAreRefused() {
        ObjectNode projectsObject = valid();
        projectsObject.putObject("projects");
        refused(projectsObject).hasMessageContaining("projects must be an array");

        ObjectNode projectsMissing = valid();
        projectsMissing.remove("projects");
        refused(projectsMissing).hasMessageContaining("projects is missing");

        ObjectNode focusMissing = valid();
        focusMissing.remove("focus");
        refused(focusMissing).hasMessageContaining("focus is missing");

        ObjectNode focusText = valid();
        focusText.put("focus", "alpha first");
        refused(focusText).hasMessageContaining("focus must be an array");

        ObjectNode urgentText = valid();
        urgentText.put("urgent", "none");
        refused(urgentText).hasMessageContaining("urgent must be an array");

        assertThatThrownBy(() -> BriefContent.parse(Json.mapper().createArrayNode(), PROJECTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BriefContent.parse(null, PROJECTS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEntryThatIsAStringIsRefused() {
        ObjectNode args = valid();
        ((ArrayNode) args.path("projects")).add("beta: fine");
        refused(args).hasMessageContaining("each entry of projects must be an object");

        ObjectNode inFocus = valid();
        ((ArrayNode) inFocus.path("focus")).add("do it");
        refused(inFocus).hasMessageContaining("each entry of focus must be an object");
    }

    @Test
    void aRunWithNoProjectsAcceptsNoProject() {
        ObjectNode args = args();
        line(args, "alpha", "Moved.");
        args.putArray("focus");

        assertThatThrownBy(() -> BriefContent.parse(args, List.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alpha");
    }

    @Test
    void projectsOfReadsTheRunsInput() {
        assertThat(BriefContent.projectsOf(JSONB.valueOf("{\"projects\":[\"a\",\"b\"]}"))).containsExactly("a", "b");
        assertThat(BriefContent.projectsOf(JSONB.valueOf("{\"trigger\":\"on_demand\",\"projects\":[\"a\",7,null]}")))
                .containsExactly("a");
        assertThat(BriefContent.projectsOf(JSONB.valueOf("{}"))).isEmpty();
        assertThat(BriefContent.projectsOf(JSONB.valueOf("{\"projects\":\"a\"}"))).isEmpty();
        assertThat(BriefContent.projectsOf(JSONB.valueOf("not json"))).isEmpty();
        assertThat(BriefContent.projectsOf(null)).isEmpty();
    }

    // ─────────────────────────── helpers ───────────────────────────

    private static AbstractThrowableAssert<?, ? extends Throwable> refused(JsonNode args) {
        return assertThatThrownBy(() -> BriefContent.parse(args, PROJECTS)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode args() {
        return Json.mapper().createObjectNode();
    }

    /** A line for each of the two projects, one focus item and one urgent entry. */
    private static ObjectNode valid() {
        ObjectNode args = args();
        line(args, "alpha", "Auth landed.");
        line(args, "beta", "Nothing moved.");
        focus(args, "alpha", "Answer s-one", "It waits on you.");
        urgent(args, "beta", "The certificate expires tonight", "Nothing else says so.");
        return args;
    }

    private static ObjectNode withStatus(String statusLine) {
        ObjectNode args = valid();
        ((ObjectNode) args.path("projects").path(0)).put("status_line", statusLine);
        return args;
    }

    private static void line(ObjectNode args, String project, String statusLine) {
        args.withArray("projects").addObject().put("project", project).put("status_line", statusLine);
    }

    private static void focus(ObjectNode args, String project, String item, String why) {
        args.withArray("focus").addObject().put("project", project).put("item", item).put("why", why);
    }

    private static void urgent(ObjectNode args, String project, String title, String why) {
        args.withArray("urgent").addObject().put("project", project).put("title", title).put("why", why);
    }
}
