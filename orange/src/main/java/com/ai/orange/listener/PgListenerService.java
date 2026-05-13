package com.ai.orange.listener;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Subscribes to Postgres {@code LISTEN} channels on a dedicated long-lived
 * JDBC connection (bypassing Hikari's pool so the connection isn't evicted at
 * {@code maxLifetime}). Each notification is republished as a Spring
 * {@link PgNotificationEvent} so the rest of the app can {@code @EventListener}
 * over normal Spring infrastructure.
 *
 * Channels subscribed (matching the triggers in V1__init_tasks.sql /
 * V3__init_dev_envs.sql): {@code task_ready}, {@code dev_env_freed}.
 */
@Service
public class PgListenerService {

    private static final Logger log = LoggerFactory.getLogger(PgListenerService.class);
    private static final List<String> CHANNELS = List.of("task_ready", "dev_env_freed");
    private static final long POLL_TIMEOUT_MS = 2_000L;
    private static final long RECONNECT_BACKOFF_MS = 2_000L;

    private final ApplicationEventPublisher events;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private volatile boolean running = false;
    private Thread loopThread;

    public PgListenerService(DataSource dataSource, ApplicationEventPublisher events) {
        this.events = events;
        if (dataSource instanceof HikariDataSource h) {
            this.jdbcUrl = h.getJdbcUrl();
            this.user = h.getUsername();
            this.password = h.getPassword();
        } else {
            // Fallback: read URL via metadata. Credentials won't be available, so
            // this path only works when datasource.url uses libpq's URL form
            // ({@code jdbc:postgresql://user:pwd@host/db}). For Spring Boot
            // defaults (Hikari) the branch above is taken.
            try (Connection probe = dataSource.getConnection()) {
                this.jdbcUrl = probe.getMetaData().getURL();
                this.user = null;
                this.password = null;
            } catch (SQLException e) {
                throw new IllegalStateException("cannot determine JDBC URL for LISTEN connection", e);
            }
        }
    }

    @PostConstruct
    void start() {
        running = true;
        loopThread = Thread.ofVirtual().name("pg-listener").start(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (loopThread != null) loopThread.interrupt();
    }

    private void loop() {
        while (running) {
            try (Connection conn = openListenConnection()) {
                try (Statement s = conn.createStatement()) {
                    for (String ch : CHANNELS) s.execute("LISTEN " + ch);
                }
                PGConnection pg = conn.unwrap(PGConnection.class);
                log.info("LISTENing on channels {}", CHANNELS);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications((int) POLL_TIMEOUT_MS);
                    if (notifications == null) continue;
                    for (PGNotification n : notifications) {
                        events.publishEvent(new PgNotificationEvent(n.getName(), n.getParameter()));
                    }
                }
            } catch (SQLException e) {
                if (!running) return;
                log.warn("LISTEN connection dropped; reconnecting in {}ms", RECONNECT_BACKOFF_MS, e);
                try {
                    Thread.sleep(RECONNECT_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Connection openListenConnection() throws SQLException {
        return user == null
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, user, password);
    }
}
