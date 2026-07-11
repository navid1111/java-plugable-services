package com.example.tweeter.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostLifecycleMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migratesCleanDatabase() throws Exception {
        migrate("clean_case");

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(columnExists(statement, "clean_case", "posts", "updated_at")).isTrue();
            assertThat(columnExists(statement, "clean_case", "posts", "deleted_at")).isTrue();
            assertThat(columnExists(statement, "clean_case", "posts", "version")).isTrue();
        }
    }

    @Test
    void migratesAndBackfillsExistingPosts() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA legacy_case");
            statement.execute("""
                    CREATE TABLE legacy_case.posts (
                        id BIGSERIAL PRIMARY KEY,
                        author_username VARCHAR(100) NOT NULL,
                        content VARCHAR(280) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO legacy_case.posts(author_username, content, created_at)
                    VALUES ('legacy-user', 'existing post', '2025-01-01T00:00:00Z')
                    """);
        }

        migrate("legacy_case");

        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT created_at, updated_at, deleted_at, version FROM legacy_case.posts")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getObject("updated_at")).isEqualTo(row.getObject("created_at"));
            assertThat(row.getObject("deleted_at")).isNull();
            assertThat(row.getLong("version")).isZero();
        }
    }

    private static void migrate(String schema) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .locations("classpath:db/migration/service")
                .load()
                .migrate();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static boolean columnExists(
            Statement statement, String schema, String table, String column) throws Exception {
        try (ResultSet result = statement.executeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = '%s' AND table_name = '%s' AND column_name = '%s'
                )
                """.formatted(schema, table, column))) {
            result.next();
            return result.getBoolean(1);
        }
    }
}
