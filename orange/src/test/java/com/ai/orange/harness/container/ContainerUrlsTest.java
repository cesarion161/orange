package com.ai.orange.harness.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Inside a container {@code localhost} is the container: a URL that means the host has to say so. */
class ContainerUrlsTest {

    @Test
    void loopbackSpellingsAreRewrittenWithPortAndPathKept() {
        assertThat(ContainerUrls.fromContainer("http://127.0.0.1:8080/mcp/worker"))
                .isEqualTo("http://host.docker.internal:8080/mcp/worker");
        assertThat(ContainerUrls.fromContainer("http://localhost:4000")).isEqualTo("http://host.docker.internal:4000");
        assertThat(ContainerUrls.fromContainer("http://LOCALHOST:4000/")).isEqualTo("http://host.docker.internal:4000/");
        assertThat(ContainerUrls.fromContainer("http://[::1]:8080/mcp/worker"))
                .isEqualTo("http://host.docker.internal:8080/mcp/worker");
        assertThat(ContainerUrls.fromContainer("http://0.0.0.0:8080/mcp/worker"))
                .isEqualTo("http://host.docker.internal:8080/mcp/worker");
        assertThat(ContainerUrls.fromContainer("https://localhost/v1?x=http://localhost:1#top"))
                .isEqualTo("https://host.docker.internal/v1?x=http://localhost:1#top");
        assertThat(ContainerUrls.fromContainer("http://localhost")).isEqualTo("http://host.docker.internal");
        assertThat(ContainerUrls.fromContainer("http://user@127.0.0.1:4000/a%2Fb"))
                .isEqualTo("http://user@host.docker.internal:4000/a%2Fb");
    }

    @Test
    void otherHostsAreUntouched() {
        for (String url : List.of(
                "http://orange.internal:8080/mcp/worker",
                "https://litellm.example.net",
                "http://10.0.0.5:4000/",
                "http://127.0.0.10:4000/",
                "http://localhost.example.net:4000/",
                "http://mylocalhost:4000/",
                "http://example.net/?next=http://localhost:8080/",
                "http://example.net/localhost",
                "http://localhost@example.net/",
                "http://host.docker.internal:8080/mcp/worker",
                "localhost:8080",
                "not a url",
                "")) {
            assertThat(ContainerUrls.fromContainer(url)).isEqualTo(url);
        }
        assertThat(ContainerUrls.fromContainer(null)).isNull();
    }
}
