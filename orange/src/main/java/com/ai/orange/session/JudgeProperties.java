package com.ai.orange.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The session judge ({@code orange.judge}; project-space spec decisions 2 and 3).
 *
 * @param enabled          whether a stop is judged; off, stops follow the rules of Phase A
 * @param hookWait         how long a stop hook waits for the verdict before the session just stops
 * @param maxAutoContinues orange answers a session at most this many times in a row without the user
 * @param pickupGrace      a verdict no stop hook took within this long is expired
 * @param startGrace       a judge run still queued after this long is given up: the stop follows the rules
 */
@ConfigurationProperties(prefix = "orange.judge")
public record JudgeProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("10m") Duration hookWait,
                              @DefaultValue("5") int maxAutoContinues,
                              @DefaultValue("2m") Duration pickupGrace,
                              @DefaultValue("60s") Duration startGrace) {}
