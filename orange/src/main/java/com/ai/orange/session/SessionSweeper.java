package com.ai.orange.session;

import com.ai.orange.attention.AttentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Time-based attention: waits that have gone on too long (see {@link SessionService#sweep}). */
@Component
public class SessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(SessionSweeper.class);

    private final SessionService sessions;
    private final AttentionProperties props;

    public SessionSweeper(SessionService sessions, AttentionProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${orange.attention.sweep:30s}")
    void sweep() {
        if (!props.sweepEnabled()) return;
        try {
            sessions.sweep();
        } catch (RuntimeException e) {
            log.warn("sweeping the waiting sessions failed: {}", e.toString());
        }
    }
}
