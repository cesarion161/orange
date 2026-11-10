package com.ai.orange.brief;

import static com.ai.orange.brief.BriefFixtures.project;
import static com.ai.orange.brief.BriefFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A push links to the brief's page only when a phone can reach orange. */
class BriefLinksTest {

    static final Brief BRIEF = BriefFixtures.brief(snapshot(project("alpha")).build(), null, null);

    static BriefLinks links(String publicUrl) {
        return new BriefLinks(new BriefProperties(true, Duration.ofSeconds(60), Duration.ofHours(2),
                Duration.ofHours(24), Duration.ofMinutes(10), Duration.ofSeconds(60), publicUrl,
                Duration.ofHours(48), Duration.ofDays(30)));
    }

    @Test
    void withoutAPublicUrlThePushCarriesNoLink() {
        assertThat(links("").click(BRIEF)).isEmpty();
        assertThat(links(null).click(BRIEF)).isEmpty();
        assertThat(links("   ").click(BRIEF)).isEmpty();
    }

    @Test
    void theLinkIsThePageWithItsKey() {
        String page = "https://orange.example/brief/" + BRIEF.id() + "?k=" + BRIEF.pageKey();
        assertThat(links("https://orange.example/").click(BRIEF)).contains(page);
        assertThat(links("https://orange.example").click(BRIEF)).contains(page);
        assertThat(links("https://orange.example/").page(BRIEF)).isEqualTo(page);
    }

    @Test
    void beforeTheServerStartedThePageIsOnlyAPath() {
        assertThat(links("").page(BRIEF)).isEqualTo("/brief/" + BRIEF.id() + "?k=" + BRIEF.pageKey());
        assertThat(BriefLinks.path(BRIEF)).isEqualTo("/brief/" + BRIEF.id() + "?k=" + BRIEF.pageKey());
    }
}
