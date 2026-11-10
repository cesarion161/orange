package com.ai.orange.brief;

import java.util.Optional;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Where a brief's page is. A phone reaches orange only through {@code orange.brief.public-url},
 * so a push carries a link only when that is set; a session on the coordinator's own host is
 * given the loopback address, learned when the web server starts.
 */
@Component
public class BriefLinks {

    private final BriefProperties props;
    private volatile int port = -1;

    public BriefLinks(BriefProperties props) {
        this.props = props;
    }

    @EventListener
    void onWebServerStarted(ServletWebServerInitializedEvent event) {
        // A separate management server has its own namespace; only the main server serves the page.
        if (event.getApplicationContext().getServerNamespace() == null) {
            port = event.getWebServer().getPort();
        }
    }

    /** The page's path with the key that opens it. */
    static String path(Brief b) {
        return "/brief/" + b.id() + "?k=" + b.pageKey();
    }

    /** The link a push carries; empty when no public url is set. */
    public Optional<String> click(Brief b) {
        return props.publicUrl().isBlank() ? Optional.empty() : Optional.of(props.publicUrl() + path(b));
    }

    /** The page's address: public when there is one, else on this host; only the path before the server started. */
    public String page(Brief b) {
        if (!props.publicUrl().isBlank()) return props.publicUrl() + path(b);
        return port > 0 ? "http://localhost:" + port + path(b) : path(b);
    }
}
