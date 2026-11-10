package com.ai.orange.run;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The run loop's knobs ({@code orange.runs}).
 *
 * @param loopEnabled whether the scheduler, reactor and reaper run (tests turn them off)
 * @param workerId    this coordinator instance's id on the runs it claims; blank → {@code <host>-<8 hex>}
 * @param slots       concurrent runs per kind, keyed by the kind's db value; unset kinds use the defaults
 * @param timeouts    per-kind run timeout, keyed by the kind's db value; unset kinds use the defaults
 * @param leaseTtl    how long a run's lease lasts without a heartbeat
 * @param heartbeat   how often a live run renews its lease
 * @param retention   how long a finished run's directory is kept
 */
@ConfigurationProperties(prefix = "orange.runs")
public record RunsProperties(
        @DefaultValue("true") boolean loopEnabled,
        @DefaultValue("") String workerId,
        @DefaultValue("6") int maxConcurrent,
        Map<String, Integer> slots,
        Map<String, Duration> timeouts,
        @DefaultValue("5m") Duration leaseTtl,
        @DefaultValue("60s") Duration heartbeat,
        @DefaultValue("5s") Duration schedulerPoll,
        @DefaultValue("30s") Duration reactorSweep,
        @DefaultValue("30s") Duration reaperSweep,
        @DefaultValue("14d") Duration retention) {

    static final Map<RunKind, Integer> DEFAULT_SLOTS = Map.of(
            RunKind.PLAN, 1, RunKind.IMPLEMENT, 4, RunKind.CI_FIX, 2, RunKind.VERIFY, 2,
            RunKind.DIFF_REVIEW, 2, RunKind.INTEGRATE, 1, RunKind.JUDGE, 2, RunKind.BRIEF, 0);
    static final Map<RunKind, Duration> DEFAULT_TIMEOUTS = Map.of(
            RunKind.PLAN, Duration.ofMinutes(30), RunKind.IMPLEMENT, Duration.ofMinutes(60),
            RunKind.CI_FIX, Duration.ofMinutes(40), RunKind.VERIFY, Duration.ofMinutes(30),
            RunKind.DIFF_REVIEW, Duration.ofMinutes(20), RunKind.INTEGRATE, Duration.ofMinutes(10),
            RunKind.JUDGE, Duration.ofMinutes(5), RunKind.BRIEF, Duration.ofMinutes(10));

    public RunsProperties {
        if (workerId == null || workerId.isBlank()) workerId = generatedWorkerId();
        slots = normalisedKeys(slots);
        timeouts = normalisedKeys(timeouts);
    }

    public int slots(RunKind kind) {
        Integer configured = slots.get(key(kind.dbValue()));
        return configured != null ? configured : DEFAULT_SLOTS.getOrDefault(kind, 0);
    }

    public Duration timeout(RunKind kind) {
        Duration configured = timeouts.get(key(kind.dbValue()));
        return configured != null ? configured : DEFAULT_TIMEOUTS.getOrDefault(kind, Duration.ofMinutes(30));
    }

    /** Effective slots per kind, for status pages. */
    public Map<RunKind, Integer> allSlots() {
        Map<RunKind, Integer> all = new HashMap<>();
        for (RunKind k : RunKind.values()) all.put(k, slots(k));
        return all;
    }

    /**
     * Map keys compared without case or punctuation, so {@code ci_fix}, {@code ci-fix} and an
     * environment variable's {@code CI_FIX} all name the same kind.
     */
    private static <V> Map<String, V> normalisedKeys(Map<String, V> map) {
        if (map == null) return Map.of();
        Map<String, V> out = new HashMap<>();
        map.forEach((k, v) -> out.put(key(k), v));
        return Map.copyOf(out);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String generatedWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "coordinator";
        }
        byte[] random = new byte[4];
        ThreadLocalRandom.current().nextBytes(random);
        return host + "-" + HexFormat.of().formatHex(random);
    }
}
