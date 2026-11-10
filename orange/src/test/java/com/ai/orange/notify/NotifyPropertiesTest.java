package com.ai.orange.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.brief.BriefProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * A variable left empty in {@code .env} binds as an empty string. For a topic or the public url
 * that must mean "the default", not an empty topic. The brief's topic has no word of its own as
 * the default: on a public ntfy server a topic's name is the only secret.
 */
class NotifyPropertiesTest {

    static <T> T bind(String prefix, Class<T> type, Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(prefix, type);
    }

    /** {@code orange.notify} as the shipped application.yaml binds it with these variables set, and no others. */
    static NotifyProperties shipped(Map<String, Object> variables) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate("orange.notify", NotifyProperties.class);
    }

    @Test
    void theAttentionTopicHasADefaultAndTheBriefFollowsIt() {
        NotifyProperties p = bind("orange.notify", NotifyProperties.class, Map.of());
        assertThat(p.ntfy().attentionTopic()).isEqualTo("attention");
        assertThat(p.ntfy().briefTopic()).isEqualTo("attention");
        assertThat(p.ntfy().configured()).isFalse();
    }

    @Test
    void anEmptyTopicMeansTheDefault() {
        NotifyProperties p = bind("orange.notify", NotifyProperties.class, Map.of(
                "orange.notify.ntfy.url", "https://ntfy.example",
                "orange.notify.ntfy.attention-topic", "",
                "orange.notify.ntfy.brief-topic", "  "));
        assertThat(p.ntfy().attentionTopic()).isEqualTo("attention");
        assertThat(p.ntfy().briefTopic()).isEqualTo("attention");
        assertThat(p.ntfy().configured()).isTrue();
    }

    @Test
    void withoutATopicOfItsOwnTheBriefGoesToTheAttentionTopic() {
        // An install from before the brief: a secret attention topic and no brief topic at all, or an empty one.
        for (Map<String, String> values : List.of(
                Map.of("orange.notify.ntfy.attention-topic", " a-long-random-name "),
                Map.of("orange.notify.ntfy.attention-topic", "a-long-random-name", "orange.notify.ntfy.brief-topic", ""))) {
            NotifyProperties p = bind("orange.notify", NotifyProperties.class, values);
            assertThat(p.ntfy().briefTopic()).as("never a word anyone can guess").isEqualTo("a-long-random-name");
        }
    }

    @Test
    void theShippedSettingsGiveTheBriefNoTopicOfItsOwn() throws Exception {
        NotifyProperties upgraded = shipped(Map.of("ORANGE_NTFY_URL", "https://ntfy.sh",
                "ORANGE_NTFY_ATTENTION_TOPIC", "a-long-random-name"));
        assertThat(upgraded.ntfy().configured()).isTrue();
        assertThat(upgraded.ntfy().attentionTopic()).isEqualTo("a-long-random-name");
        assertThat(upgraded.ntfy().briefTopic()).isEqualTo("a-long-random-name");

        NotifyProperties both = shipped(Map.of("ORANGE_NTFY_ATTENTION_TOPIC", "a-long-random-name",
                "ORANGE_NTFY_BRIEF_TOPIC", "another-one"));
        assertThat(both.ntfy().briefTopic()).isEqualTo("another-one");
        assertThat(shipped(Map.of()).ntfy().briefTopic()).isEqualTo("attention");
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
