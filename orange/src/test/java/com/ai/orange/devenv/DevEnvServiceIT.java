package com.ai.orange.devenv;

import static com.ai.orange.db.jooq.Tables.DEV_ENVS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the {@link DevEnvService} concurrency contract:
 * <ul>
 *   <li>N concurrent acquires against a pool of N envs all succeed with
 *       distinct envs (no duplicates).</li>
 *   <li>An (N+1)-th waiter blocks until somebody calls {@link DevEnvService#release},
 *       then unblocks within a few seconds.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DevEnvServiceIT {

    @Autowired DevEnvService service;
    @Autowired DevEnvRepository repo;
    @Autowired DSLContext dsl;

    @DynamicPropertySource
    static void disableAutoStart(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
    }

    @BeforeEach
    void resetPool() {
        // Restore every env to free between tests so we don't leak state.
        dsl.update(DEV_ENVS)
                .set(DEV_ENVS.STATUS, DevEnvStatus.FREE.dbValue())
                .setNull(DEV_ENVS.LEASED_BY)
                .setNull(DEV_ENVS.LEASED_AT)
                .execute();
    }

    @Test
    void five_concurrent_acquires_get_five_distinct_envs() throws Exception {
        int free = repo.countByStatus(DevEnvStatus.FREE);
        assertThat(free).as("V3 seeds 5 dev_envs").isEqualTo(5);

        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(free);

        for (int i = 0; i < free; i++) {
            String holder = "holder-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    Optional<DevEnv> env = service.acquire(holder, Duration.ofSeconds(5));
                    env.ifPresent(e -> {
                        if (!claimed.add(e.id())) duplicates.incrementAndGet();
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(duplicates.get()).as("no env should be leased twice").isZero();
        assertThat(claimed).hasSize(free);
        assertThat(repo.countByStatus(DevEnvStatus.FREE)).isZero();
    }

    @Test
    void sixth_acquire_blocks_until_release_then_unblocks() throws Exception {
        // Lease all 5.
        for (int i = 0; i < 5; i++) {
            assertThat(service.acquire("seed-" + i, Duration.ofSeconds(2))).isPresent();
        }
        assertThat(repo.countByStatus(DevEnvStatus.FREE)).isZero();

        AtomicReference<Optional<DevEnv>> result = new AtomicReference<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);

        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                waiterStarted.countDown();
                result.set(service.acquire("late-arrival", Duration.ofSeconds(20)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                waiterDone.countDown();
            }
        });

        waiterStarted.await();
        // Give the waiter long enough to enter its first poll.
        Thread.sleep(500);
        assertThat(waiter.isAlive())
                .as("late-arrival should still be blocked, pool fully leased")
                .isTrue();

        // Free up one env. The trigger fires NOTIFY and the LISTEN bridge
        // re-publishes it as a Spring event picked up by DevEnvService.
        UUID toRelease = repo.findByLeasedBy("seed-0").get(0).id();
        assertThat(service.release(toRelease)).isTrue();

        // Waiter should unblock within the next polling tick (5s) — give it 15.
        assertThat(waiterDone.await(15, TimeUnit.SECONDS))
                .as("late-arrival should pick up the freed env")
                .isTrue();
        assertThat(result.get()).isPresent();
        assertThat(result.get().get().leasedBy()).isEqualTo("late-arrival");
    }

    @Test
    void acquire_returns_empty_on_timeout_when_pool_exhausted() throws Exception {
        for (int i = 0; i < 5; i++) {
            service.acquire("seed-" + i, Duration.ofSeconds(2));
        }
        Optional<DevEnv> miss = service.acquire("late", Duration.ofSeconds(1));
        assertThat(miss).isEmpty();
    }
}
