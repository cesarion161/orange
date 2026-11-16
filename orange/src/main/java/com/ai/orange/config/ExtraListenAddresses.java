package com.ai.orange.config;

import java.util.Arrays;
import java.util.List;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * More addresses orange listens on, beside {@code server.address} ({@code orange.server.extra-addresses},
 * {@code ORANGE_SERVER_EXTRA_ADDRESSES}, comma-separated; blank: none). On the VPS orange listens on
 * 127.0.0.1, where {@code tailscale serve} reaches it, and on the run network's gateway, where its
 * contained runs reach {@code /mcp/worker} ({@link RunNetworkGuard} refuses them everything else);
 * on no public address. Each address gets a connector of its own on orange's port.
 */
@Component
public class ExtraListenAddresses implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final List<String> addresses;

    public ExtraListenAddresses(@Value("${orange.server.extra-addresses:}") String addresses) {
        this.addresses = Arrays.stream(addresses == null ? new String[0] : addresses.split(","))
                .map(String::strip).filter(a -> !a.isEmpty()).toList();
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        for (String address : addresses) {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(factory.getPort());
            connector.setProperty("address", address);
            factory.addAdditionalConnectors(connector);
        }
    }

    public List<String> addresses() {
        return addresses;
    }
}
