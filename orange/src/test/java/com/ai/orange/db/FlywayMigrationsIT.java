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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

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

        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            Set<String> tables = new HashSet<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            assertThat(tables).contains("tasks", "task_edges", "task_events",
                    "agents", "agent_runs", "dev_envs");

            // Spot-check a partial index, an enum CHECK, and the seeded env pool.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM pg_indexes " +
                         "WHERE indexname = 'idx_tasks_ready_queue'")) {
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
}
