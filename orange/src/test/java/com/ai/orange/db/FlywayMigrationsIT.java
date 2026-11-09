package com.ai.orange.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Flyway migrations actually apply against the same Postgres
 * version we run in compose, and that the resulting schema looks right.
 */
@Testcontainers
class FlywayMigrationsIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                    .withDatabaseName("orange")
                    .withUsername("orange")
                    .withPassword("secret");

    @Test
    void migrations_apply_cleanly_and_create_expected_tables() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .contains("1", "2", "3");

        try (Connection c = connect(POSTGRES.getJdbcUrl())) {

            Set<String> tables = new HashSet<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            assertThat(tables).contains("tasks", "task_edges", "task_events", "dev_envs");
            assertThat(tables).doesNotContain("task_claims");

            // Spot-check a partial index, an enum CHECK, and the seeded env pool.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM pg_indexes " +
                         "WHERE indexname = 'idx_tasks_ready'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM dev_envs")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(5);
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT uuidv7() IS NOT NULL")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }

    /** Rows written by v1 (V13 schema) come out of V14 in FSM v2 shape. */
    @Test
    void v1RowsMapToFsmV2() throws Exception {
        try (Connection admin = connect(POSTGRES.getJdbcUrl()); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE v1data");
        }
        String url = POSTGRES.getJdbcUrl().replaceFirst("/orange(\\?|$)", "/v1data$1");
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("13").load().migrate();

        Map<String, UUID> ids = new LinkedHashMap<>();
        try (Connection c = connect(url)) {
            for (String status : List.of("pending", "ready", "in_progress", "pr_open", "dev_ready",
                    "in_test", "test_done", "failed", "cancelled")) {
                ids.put(status, insertTask(c, status, "tester", "{}"));
            }
            ids.put("with_pr", insertTask(c, "pr_open", "dev",
                    "{\"pr_repo\":\"o/r\",\"pr_number\":7,\"pr_url\":\"u\"}"));
            ids.put("chore", insertTask(c, "ready", "chore", "{}"));
        }

        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection c = connect(url)) {
            assertThat(column(c, ids.get("pending"), "status")).isEqualTo("pending");
            assertThat(column(c, ids.get("ready"), "status")).isEqualTo("ready");
            assertThat(column(c, ids.get("in_progress"), "status")).isEqualTo("ready");
            assertThat(column(c, ids.get("pr_open"), "status")).as("a v1 PR waits for a person").isEqualTo("needs_human");
            assertThat(column(c, ids.get("dev_ready"), "status")).isEqualTo("integrated");
            assertThat(column(c, ids.get("in_test"), "status")).isEqualTo("integrated");
            assertThat(column(c, ids.get("test_done"), "status")).isEqualTo("integrated");
            assertThat(column(c, ids.get("failed"), "status")).isEqualTo("failed");
            assertThat(column(c, ids.get("cancelled"), "status")).isEqualTo("cancelled");

            UUID pr = ids.get("with_pr");
            assertThat(column(c, pr, "pr_number")).isEqualTo("7");
            assertThat(column(c, pr, "repo")).isEqualTo("o/r");
            assertThat(column(c, pr, "pr_url")).isEqualTo("u");
            assertThat(column(c, pr, "branch")).isEqualTo("orange/" + pr);
            assertThat(column(c, pr, "role")).isEqualTo("implementer");
            assertThat(column(c, pr, "failures")).isEqualTo("0");
            assertThat(column(c, ids.get("pending"), "role")).isEqualTo("implementer");
            assertThat(column(c, ids.get("chore"), "role")).isEqualTo("chore");
            assertThat(column(c, ids.get("pending"), "branch")).isNull();

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.columns "
                         + "WHERE table_name = 'tasks' AND column_name IN ('reviewed_sha', 'review_verdict')")) {
                rs.next();
                assertThat(rs.getInt(1)).as("the diff-review verdict is evidence, not a task column").isZero();
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT to_regclass('public.task_claims') IS NULL")) {
                rs.next();
                assertThat(rs.getBoolean(1)).as("task_claims dropped").isTrue();
            }
        }
    }

    private static UUID insertTask(Connection c, String status, String role, String metadata) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tasks (title, role, status, metadata) VALUES (?, ?, ?, ?::jsonb) RETURNING id")) {
            ps.setString(1, "v1 " + status);
            ps.setString(2, role);
            ps.setString(3, status);
            ps.setString(4, metadata);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static String column(Connection c, UUID id, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + column + "::text FROM tasks WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
