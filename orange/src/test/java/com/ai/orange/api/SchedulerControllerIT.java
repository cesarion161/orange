package com.ai.orange.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.run.SchedulerPause;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;

/** The operator's stop switch: pause with a reason, see it, resume. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SchedulerControllerIT {

    @LocalServerPort int port;
    @Autowired SchedulerPause pause;
    final RestTemplate http = new RestTemplate();

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void resume() {
        pause.resume();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pauseShowsTheReasonAndResumeClearsIt() {
        http.postForObject(url("/scheduler/pause"), Map.of("reason", "maintenance", "minutes", 10), Map.class);

        Map<String, Object> status = http.getForObject(url("/scheduler"), Map.class);
        Map<String, Object> paused = (Map<String, Object>) status.get("paused");
        assertThat(paused).containsEntry("reason", "maintenance");
        OffsetDateTime until = OffsetDateTime.parse((String) paused.get("until"));
        assertThat(until).isBetween(OffsetDateTime.now().plusMinutes(9), OffsetDateTime.now().plusMinutes(11));
        assertThat(paused).containsKey("since");
        assertThat(status).containsEntry("max_concurrent", 6);
        Map<String, Object> implement = (Map<String, Object>) ((Map<String, Object>) status.get("runs")).get("implement");
        assertThat(implement).containsEntry("slots", 4).containsKeys("queued", "running");

        http.postForObject(url("/scheduler/resume"), null, Map.class);

        assertThat(http.getForObject(url("/scheduler"), Map.class).get("paused")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pauseWithoutMinutesIsOpenEnded() {
        Map<String, Object> status = http.postForObject(url("/scheduler/pause"), Map.of(), Map.class);

        Map<String, Object> paused = (Map<String, Object>) status.get("paused");
        assertThat(paused).containsEntry("reason", "operator");
        assertThat(paused.get("until")).isNull();
    }
}
