package com.ai.orange.config;

import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.mcp.WorkerMcpConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps contained runs to the one endpoint that is theirs. A container on the run network reaches
 * the coordinator's port (it has to, for {@code /mcp/worker}), and with it the whole operator API,
 * which has no authentication. So a request that comes from one of the run network's subnets may
 * call {@code /mcp/worker} and nothing else ({@link SecurityConfig} asks {@link #refuses}).
 *
 * <p>This rests on where Docker lets a container's request arrive from. On a Linux bridge that is
 * the container's own address. On Docker Desktop it is the loopback address, like the user's own
 * requests, and the guard sees nothing: there, container mode is for development, and
 * {@code orange.api.token} guards at least the session and steering endpoints.
 *
 * <p>Until Docker has said which subnets the run network has (it is asked before the web server
 * starts, and again every 30 s while it does not answer), nobody can tell a run's request from
 * anyone's: a container that outlived the previous coordinator may be calling. In container mode
 * the guard then lets through only this host itself (the loopback address) and {@code /mcp/worker}.
 *
 * <p>The address is the connection's ({@code getRemoteAddr()}). orange does not read forwarding
 * headers ({@code server.forward-headers-strategy} is not set); if it ever does, a run could name
 * any address it likes.
 */
@Component
public class RunNetworkGuard {

    private static final Logger log = LoggerFactory.getLogger(RunNetworkGuard.class);

    private static final Pattern DOTTED_QUAD = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Supplier<List<String>> subnets;
    private final boolean warnAtStart;
    private volatile Parsed parsed = new Parsed(List.of(), List.of());

    static final String ONLY_THE_WORKER_MCP = "runs reach only /mcp/worker";
    static final String NETWORK_UNKNOWN = "container mode, and Docker has not said yet where runs call from: "
            + "until it does, only this host itself reaches orange";

    @Autowired
    public RunNetworkGuard(ContainerRuntime containers, @Value("${orange.api.token:}") String apiToken) {
        this(() -> !containers.enabled() ? List.of() : containers.networkSeen() ? containers.subnets() : null,
                containers.enabled() && (apiToken == null || apiToken.isBlank()));
    }

    /**
     * @param subnets the run network's subnets in CIDR notation, as they are now; empty when there are
     *                no contained runs (process mode), null while container mode does not know them yet
     */
    public RunNetworkGuard(Supplier<List<String>> subnets) {
        this(subnets, false);
    }

    private RunNetworkGuard(Supplier<List<String>> subnets, boolean warnAtStart) {
        this.subnets = subnets;
        this.warnAtStart = warnAtStart;
    }

    /** Whether the request comes from a run's container and asks for anything but the worker MCP endpoint. */
    public boolean refuses(HttpServletRequest request) {
        return refusal(request) != null;
    }

    /** Why the request is refused, for whoever sent it; null when it is not. */
    public String refusal(HttpServletRequest request) {
        // The raw URI, compared as it is: any other spelling of the path is refused, not interpreted.
        if (WorkerMcpConfig.PATH.equals(request.getRequestURI())) return null;
        if (subnets.get() == null) {
            // Any address may be a run's container as long as the run network is unknown; this host's own is not.
            return loopback(request.getRemoteAddr()) ? null : NETWORK_UNKNOWN;
        }
        return fromRunNetwork(request.getRemoteAddr()) ? ONLY_THE_WORKER_MCP : null;
    }

    /**
     * Whether {@code remoteAddr} lies in one of the run network's subnets. False when the subnets
     * are not known (process mode never looks; for container mode see {@link #refusal}) or the
     * address is not an IP literal.
     */
    public boolean fromRunNetwork(String remoteAddr) {
        List<Cidr> cidrs = cidrs();
        if (cidrs.isEmpty() || remoteAddr == null) return false;
        byte[] address = literal(remoteAddr.strip());
        if (address == null) return false;
        for (Cidr c : cidrs) {
            if (c.contains(address)) return true;
        }
        return false;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (warnAtStart) {
            log.warn("container mode without orange.api.token: where Docker delivers a container's requests from "
                    + "the loopback address (Docker Desktop), a run can call the session and steering endpoints "
                    + "like you can. Set ORANGE_API_TOKEN.");
        }
    }

    private static boolean loopback(String remoteAddr) {
        byte[] address = remoteAddr == null ? null : literal(remoteAddr.strip());
        if (address == null) return false;
        try {
            return InetAddress.getByAddress(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private List<Cidr> cidrs() {
        List<String> now = subnets.get();
        if (now == null) now = List.of();
        Parsed p = parsed;
        if (!p.source().equals(now)) {
            List<Cidr> cidrs = new ArrayList<>();
            for (String subnet : now) {
                Cidr c = Cidr.parse(subnet);
                if (c == null) log.warn("the run network's subnet '{}' is not CIDR notation; requests from it are not recognised", subnet);
                else cidrs.add(c);
            }
            p = new Parsed(List.copyOf(now), List.copyOf(cidrs));
            parsed = p;
        }
        return p.cidrs();
    }

    /** The bytes of an IP literal (an IPv4-mapped IPv6 address as IPv4), or null. Never a name lookup. */
    private static byte[] literal(String text) {
        if (text.isEmpty()) return null;
        // Java also reads "172.18.0" and "2886860802" as IPv4 addresses; nothing writes an address that way.
        if (text.indexOf(':') < 0 && !DOTTED_QUAD.matcher(text).matches()) return null;
        // A zone ("fe80::1%eth0") names an interface, not a different address.
        int zone = text.indexOf('%');
        try {
            return InetAddress.ofLiteral(zone < 0 ? text : text.substring(0, zone)).getAddress();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The subnets as last read, and what they parsed to. */
    private record Parsed(List<String> source, List<Cidr> cidrs) {}

    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String text) {
            if (text == null) return null;
            int slash = text.indexOf('/');
            if (slash <= 0) return null;
            byte[] network = literal(text.substring(0, slash).strip());
            if (network == null) return null;
            int prefix;
            try {
                prefix = Integer.parseInt(text.substring(slash + 1).strip());
            } catch (NumberFormatException e) {
                return null;
            }
            return prefix < 0 || prefix > network.length * 8 ? null : new Cidr(network, prefix);
        }

        boolean contains(byte[] address) {
            // An IPv4 address is never in an IPv6 subnet, nor the other way round.
            if (address.length != network.length) return false;
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (address[i] != network[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[whole] & mask) == (network[whole] & mask);
        }
    }
}
