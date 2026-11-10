package com.ai.orange.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.brief.BriefProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * A variable left empty in {@code .env} binds as an empty string. For a topic or the public url
 * that must mean "the default", not an empty topic.
 */
class NotifyPropertiesTest {

    static <T> T bind(String prefix, Class<T> type, Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(prefix, type);
    }

    @Test
    void theTopicsHaveDefaults() {
        NotifyProperties p = bind("orange.notify", NotifyProperties.class, Map.of());
        assertThat(p.ntfy().attentionTopic()).isEqualTo("attention");
        assertThat(p.ntfy().briefTopic()).isEqualTo("brief");
        assertThat(p.ntfy().configured()).isFalse();
    }

    @Test
    void anEmptyTopicMeansTheDefault() {
        NotifyProperties p = bind("orange.notify", NotifyProperties.class, Map.of(
                "orange.notify.ntfy.url", "https://ntfy.example",
                "orange.notify.ntfy.attention-topic", "",
                "orange.notify.ntfy.brief-topic", "  "));
        assertThat(p.ntfy().attentionTopic()).isEqualTo("attention");
        assertThat(p.ntfy().briefTopic()).isEqualTo("brief");
        assertThat(p.ntfy().configured()).isTrue();
    }

    @Test
    void aGivenTopicIsKept() {
        NotifyProperties p = bind("orange.notify", NotifyProperties.class, Map.of(
                "orange.notify.ntfy.attention-topic", "a-long-random-name",
                "orange.notify.ntfy.brief-topic", " another-one "));
        assertThat(p.ntfy().attentionTopic()).isEqualTo("a-long-random-name");
        assertThat(p.ntfy().briefTopic()).isEqualTo("another-one");
    }

    @Test
    void anEmptyPublicUrlMeansNoLink() {
        BriefProperties p = bind("orange.brief", BriefProperties.class, Map.of("orange.brief.public-url", ""));
        assertThat(p.enabled()).isTrue();
        assertThat(p.publicUrl()).isEmpty();
        assertThat(bind("orange.brief", BriefProperties.class, Map.of("orange.brief.public-url",
                " https://orange.example/ ")).publicUrl()).isEqualTo("https://orange.example");
        assertThat(bind("orange.brief", BriefProperties.class, Map.of("orange.brief.enabled", "false")).enabled())
                .isFalse();
    }
}
