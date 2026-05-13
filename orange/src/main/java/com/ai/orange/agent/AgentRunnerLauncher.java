package com.ai.orange.agent;

import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Thin Spring-managed factory that builds {@link AgentRunnerProcess} instances
 * from the configured {@link AgentRunnerProperties}.
 */
@Service
public class AgentRunnerLauncher {

    private final AgentRunnerProperties props;

    public AgentRunnerLauncher(AgentRunnerProperties props) {
        this.props = props;
    }

    public AgentRunnerProcess launch(Command.Start start, Consumer<Event> eventCallback) throws IOException {
        return AgentRunnerProcess.launch(
                List.of(props.python(), "-u", "-m", props.module()),
                props.cancelGrace(),
                start,
                eventCallback);
    }

    public AgentRunnerProperties properties() {
        return props;
    }
}
