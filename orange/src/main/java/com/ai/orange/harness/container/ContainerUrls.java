package com.ai.orange.harness.container;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A loopback URL as a container has to spell it. Inside a container {@code localhost} is the
 * container itself; the host is {@link #HOST}, which every online container resolves
 * ({@code --add-host host.docker.internal:host-gateway}).
 */
public final class ContainerUrls {

    public static final String HOST = "host.docker.internal";

    /** scheme://[userinfo@] | a loopback host | [:port][/path][?query][#fragment] */
    private static final Pattern LOOPBACK = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9+.-]*://(?:[^/?#@]*@)?)(?:localhost|127\\.0\\.0\\.1|\\[::1]|0\\.0\\.0\\.0)((?::\\d*)?(?:[/?#].*)?)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ContainerUrls() {}

    /**
     * {@code url} with a host of {@code localhost}, {@code 127.0.0.1}, {@code [::1]} or
     * {@code 0.0.0.0} replaced by {@link #HOST}; port, path and query stay. Any other URL is
     * returned as it is.
     */
    public static String fromContainer(String url) {
        if (url == null) return null;
        Matcher m = LOOPBACK.matcher(url);
        return m.matches() ? m.group(1) + HOST + m.group(2) : url;
    }
}
