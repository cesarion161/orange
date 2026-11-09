package com.ai.orange.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Where a run's agent reaches the worker MCP endpoint. {@code orange.mcp.worker-url} wins when set
 * (containers will need it); otherwise it is this coordinator's own port on the loopback address,
 * learned when the web server starts.
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
}
