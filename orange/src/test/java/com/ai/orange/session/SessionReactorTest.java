package com.ai.orange.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.session.SessionReactor.Decision;
import com.ai.orange.session.SessionReactor.Sweep;
import com.ai.orange.session.SessionReactor.Wait;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The session rule table of Phase A. */
class SessionReactorTest {

    static final Duration ESCALATE = Duration.ofMinutes(15);
    static final Duration IDLE = Duration.ofMinutes(20);

    static Decision on(String status, String waiting, String type) {
        return SessionReactor.onEvent(status, waiting, type, null, false, false);
    }

    @Test
    void aStopThatAsksFilesAQuestion() {
        Decision d = SessionReactor.onEvent(Session.ACTIVE, null, NeutralEvent.STOPPED, null, true, false);
        assertThat(d.status()).isEqualTo(Session.STOPPED);
        assertThat(d.waiting()).isEqualTo(Wait.QUESTION);
        assertThat(d.raise()).isEqualTo(AttentionItem.SESSION_QUESTION);
        assertThat(d.resolveOpen()).isTrue();
    }

    @Test
    void aStopWithNothingToAskOnlyStartsTheIdleClock() {
        Decision d = on(Session.ACTIVE, null, NeutralEvent.STOPPED);
        assertThat(d.status()).isEqualTo(Session.STOPPED);
        assertThat(d.waiting()).isEqualTo(Wait.FINISHED);
        assertThat(d.raise()).isNull();
    }

    @Test
    void aStopLeavesAnAnsweredPermissionPromptBehind() {
        Decision d = on(Session.WAITING_ON_USER, Session.WAIT_PERMISSION, NeutralEvent.STOPPED);
        assertThat(d.resolveOpen()).isTrue();
        assertThat(d.resolvedBy()).isEqualTo("stopped");
    }

    @Test
    void aUserPromptEndsEveryWait() {
        for (String waiting : new String[] {Session.WAIT_QUESTION, Session.WAIT_PERMISSION, Session.WAIT_FINISHED, null}) {
            Decision d = on(Session.STOPPED, waiting, NeutralEvent.USER_PROMPT);
            assertThat(d.status()).isEqualTo(Session.ACTIVE);
            assertThat(d.waiting()).isEqualTo(Wait.CLEAR);
            assertThat(d.resolveOpen()).isTrue();
            assertThat(d.resolvedBy()).isEqualTo("user_prompt");
            assertThat(d.userPrompt()).isTrue();
        }
    }

    @Test
    void aPermissionPromptWaitsOnTheUser() {
        Decision d = SessionReactor.onEvent(Session.ACTIVE, null, NeutralEvent.WAITING, "permission", false, false);
        assertThat(d.status()).isEqualTo(Session.WAITING_ON_USER);
        assertThat(d.waiting()).isEqualTo(Wait.PERMISSION);
        assertThat(d.raise()).isEqualTo(AttentionItem.SESSION_WAITING);
        assertThat(d.resolveOpen()).isFalse();
    }

    @Test
    void aSecondPromptOfTheSameWaitChangesNothing() {
        Decision d = SessionReactor.onEvent(Session.WAITING_ON_USER, Session.WAIT_PERMISSION, NeutralEvent.WAITING,
                "permission", false, false);
        assertThat(d).isEqualTo(Decision.NOTHING);
    }

    @Test
    void theIdleReminderChangesNothing() {
        assertThat(SessionReactor.onEvent(Session.STOPPED, Session.WAIT_QUESTION, NeutralEvent.WAITING, "idle", false,
                false)).isEqualTo(Decision.NOTHING);
        assertThat(SessionReactor.onEvent(Session.STOPPED, Session.WAIT_FINISHED, NeutralEvent.WAITING, "idle", false,
                false)).isEqualTo(Decision.NOTHING);
    }

    @Test
    void activityEndsAWaitButIsNothingForAWorkingSession() {
        Decision resumed = on(Session.WAITING_ON_USER, Session.WAIT_PERMISSION, NeutralEvent.ACTIVE);
        assertThat(resumed.status()).isEqualTo(Session.ACTIVE);
        assertThat(resumed.waiting()).isEqualTo(Wait.CLEAR);
        assertThat(resumed.resolvedBy()).isEqualTo("resumed");
        assertThat(resumed.userPrompt()).isFalse();
        assertThat(on(Session.ACTIVE, null, NeutralEvent.ACTIVE)).isEqualTo(Decision.NOTHING);
    }

    @Test
    void theEndResolvesWhatWasOpen() {
        Decision d = on(Session.STOPPED, Session.WAIT_QUESTION, NeutralEvent.SESSION_ENDED);
        assertThat(d.status()).isEqualTo(Session.ENDED);
        assertThat(d.waiting()).isEqualTo(Wait.CLEAR);
        assertThat(d.resolveOpen()).isTrue();
    }

    @Test
    void aStartOnlyRevivesAnEndedSession() {
        assertThat(on(Session.STOPPED, Session.WAIT_QUESTION, NeutralEvent.SESSION_STARTED)).isEqualTo(Decision.NOTHING);
        assertThat(on(Session.ACTIVE, null, NeutralEvent.SESSION_STARTED)).isEqualTo(Decision.NOTHING);
        assertThat(on(Session.ENDED, null, NeutralEvent.SESSION_STARTED).status()).isEqualTo(Session.ACTIVE);
    }

    @Test
    void anArchivedProjectsSessionsAreFollowedButFileNothing() {
        Decision stop = SessionReactor.onEvent(Session.ACTIVE, null, NeutralEvent.STOPPED, null, true, true);
        assertThat(stop.status()).isEqualTo(Session.STOPPED);
        assertThat(stop.raise()).isNull();
        Decision wait = SessionReactor.onEvent(Session.ACTIVE, null, NeutralEvent.WAITING, "permission", false, true);
        assertThat(wait.status()).isEqualTo(Session.WAITING_ON_USER);
        assertThat(wait.raise()).isNull();
    }

    @Test
    void sweepEscalatesUnansweredQuestionsAndPrompts() {
        for (String reason : new String[] {Session.WAIT_QUESTION, Session.WAIT_PERMISSION}) {
            assertThat(SessionReactor.onSweep(reason, Duration.ofMinutes(14), ESCALATE, IDLE, false))
                    .isEqualTo(new Sweep(false, false));
            assertThat(SessionReactor.onSweep(reason, Duration.ofMinutes(15), ESCALATE, IDLE, false))
                    .isEqualTo(new Sweep(true, false));
        }
    }

    @Test
    void sweepFilesAFinishedSessionOnlyAfterTheIdleTime() {
        assertThat(SessionReactor.onSweep(Session.WAIT_FINISHED, Duration.ofMinutes(19), ESCALATE, IDLE, false))
                .isEqualTo(new Sweep(false, false));
        assertThat(SessionReactor.onSweep(Session.WAIT_FINISHED, Duration.ofMinutes(20), ESCALATE, IDLE, false))
                .isEqualTo(new Sweep(false, true));
    }

    @Test
    void sweepLeavesMutedAndWorkingSessionsAlone() {
        assertThat(SessionReactor.onSweep(Session.WAIT_QUESTION, Duration.ofHours(2), ESCALATE, IDLE, true))
                .isEqualTo(new Sweep(false, false));
        assertThat(SessionReactor.onSweep(null, Duration.ofHours(2), ESCALATE, IDLE, false))
                .isEqualTo(new Sweep(false, false));
    }
}
