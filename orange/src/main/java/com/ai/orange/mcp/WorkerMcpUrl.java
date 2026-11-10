package com.ai.orange.mcp;

import com.ai.orange.harness.container.ContainerUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Where a run's agent reaches the worker MCP endpoint. {@code orange.mcp.worker-url} wins when
 * set; otherwise it is this coordinator's own port on the loopback address, learned when the web
 * server starts. A run in a container gets the same URL with the loopback address spelled as the
 * container has to ({@link ContainerUrls}).
 */
@Component
public class WorkerMcpUrl {

    private final String configured;
    private volatile int port = -1;

    public WorkerMcpUrl(@Value("${orange.mcp.worker-url:}") String configured) {
        this.configured = configured == null ? "" : configured.trim();
    }

    @EventListener
    void onWebServerStarted(ServletWebServerInitializedEvent event) {
        // A separate management server has its own namespace; only the main server serves MCP.
        if (event.getApplicationContext().getServerNamespace() == null) {
            port = event.getWebServer().getPort();
        }
    }

    public String url() {
        if (!configured.isEmpty()) return configured;
        if (port <= 0) {
            throw new IllegalStateException("the web server has not started, so the worker MCP URL is unknown");
        }
        return "http://127.0.0.1:" + port + WorkerMcpConfig.PATH;
    }

    /** {@link #url()} as a run reaches it: from inside a container the host is not {@code localhost}. */
    public String url(boolean contained) {
        return contained ? ContainerUrls.fromContainer(url()) : url();
    }
}
