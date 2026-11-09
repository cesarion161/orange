package com.ai.orange.run;

import com.ai.orange.gateway.GatewayClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LiteLLM books a request's spend after it answers, so a gateway run's cost read at exit can be
 * short. Every minute this re-reads the spend of finished runs' keys (by alias) and records it;
 * once a key's spend has not moved for ten minutes the key is deleted, which ends the sync.
 */
@Component
public class SpendSync {

    private static final Logger log = LoggerFactory.getLogger(SpendSync.class);
    /** Spend unchanged for this long is final. */
    static final Duration SETTLED = Duration.ofMinutes(10);

    private final RunRepository runs;
    private final GatewayClient gateway;
    private final RunsProperties props;

    public SpendSync(RunRepository runs, GatewayClient gateway, RunsProperties props) {
        this.runs = runs;
        this.gateway = gateway;
        this.props = props;
    }

    public void sync() {
        for (Run run : runs.findSpendSyncCandidates()) {
            try {
                syncOne(run);
            } catch (RuntimeException e) {
                log.warn("spend sync for run {} ({}) failed: {}", run.id(), run.gatewayKeyAlias(), e.getMessage());
            }
        }
    }

    private void syncOne(Run run) {
        Optional<BigDecimal> spend = gateway.spendByAlias(run.gatewayKeyAlias());
        if (spend.isEmpty()) {
            // Deleted elsewhere (or never minted): nothing left to sync; the cost at finish stands.
            runs.markKeyDeleted(run.id());
            return;
        }
        BigDecimal recorded = run.costUsd() == null ? BigDecimal.ZERO : run.costUsd();
        if (spend.get().compareTo(recorded) != 0) {
            runs.recordSpend(run.id(), spend.get());
            log.info("run {}: spend {} -> {}", run.id(), recorded, spend.get());
            return;
        }
        OffsetDateTime since = run.spendSyncedAt() != null ? run.spendSyncedAt() : run.finishedAt();
        if (since != null && since.isBefore(OffsetDateTime.now().minus(SETTLED))) {
            gateway.deleteByAlias(run.gatewayKeyAlias());
            runs.markKeyDeleted(run.id());
            log.info("run {}: spend settled at {}; deleted key {}", run.id(), recorded, run.gatewayKeyAlias());
        }
    }

    @Scheduled(fixedDelayString = "60s", initialDelayString = "60s")
    void scheduled() {
        if (props.loopEnabled()) sync();
    }
}
