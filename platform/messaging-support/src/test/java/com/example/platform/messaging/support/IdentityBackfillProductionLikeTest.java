package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

/**
 * Production-like identity migration drill: two independently owned tables,
 * 50,000 legacy references, a pre-change snapshot, restore, and forced failure.
 */
class IdentityBackfillProductionLikeTest {

    private static final int USERS = 250;
    private static final int ROWS_PER_TABLE = 25_000;
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static HttpServer authExport;
    private static String authBaseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate((DataSource) dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        authExport = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        authExport.createContext("/internal/users/export", exchange -> {
            byte[] body = exportBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        authExport.start();
        authBaseUrl = "http://localhost:" + authExport.getAddress().getPort();
    }

    @AfterAll
    static void tearDown() {
        if (authExport != null) authExport.stop(0);
        POSTGRES.stop();
    }

    @Test
    void backfillPreservesRowsAndSnapshotRollbackCanBeReapplied() {
        createProductionLikeCopy();
        String postsChecksum = checksum("legacy_posts");
        String commentsChecksum = checksum("legacy_comments");
        jdbc.execute("CREATE TABLE snapshot_posts AS TABLE legacy_posts");
        jdbc.execute("CREATE TABLE snapshot_comments AS TABLE legacy_comments");

        long started = System.nanoTime();
        UserIdentityBackfill.Report first = backfill(targets()).run();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(USERS, first.exportedUsers());
        assertEquals(ROWS_PER_TABLE * 2, first.updatedRows());
        assertEquals(0, first.unresolvedRows());
        assertEquals(ROWS_PER_TABLE * 2L, resolvedRows());
        assertEquals(postsChecksum, checksum("legacy_posts"));
        assertEquals(commentsChecksum, checksum("legacy_comments"));

        // Snapshot rollback drill: restore the exact pre-backfill copy, then prove the
        // idempotent backfill can safely be run again.
        transactions.executeWithoutResult(status -> {
            jdbc.execute("TRUNCATE legacy_posts, legacy_comments");
            jdbc.execute("INSERT INTO legacy_posts SELECT * FROM snapshot_posts");
            jdbc.execute("INSERT INTO legacy_comments SELECT * FROM snapshot_comments");
        });
        assertEquals(ROWS_PER_TABLE * 2L, unresolvedRows());
        assertEquals(postsChecksum, checksum("legacy_posts"));
        assertEquals(commentsChecksum, checksum("legacy_comments"));

        UserIdentityBackfill.Report reapplied = backfill(targets()).run();
        assertEquals(ROWS_PER_TABLE * 2, reapplied.updatedRows());
        assertEquals(0, reapplied.unresolvedRows());
        UserIdentityBackfill.Report idempotent = backfill(targets()).run();
        assertEquals(0, idempotent.updatedRows());
        assertEquals(0, idempotent.unresolvedRows());
        System.out.printf("identity_backfill_drill rows=%d users=%d elapsed_ms=%d unresolved=0 rollback=passed%n",
                ROWS_PER_TABLE * 2, USERS, elapsedMs);
    }

    @Test
    void failedBackfillRollsBackAllEarlierTargetUpdates() {
        createProductionLikeCopy();
        List<UserIdentityBackfill.Target> targets = List.of(
                new UserIdentityBackfill.Target(
                        "UPDATE legacy_posts SET user_id=? WHERE username=? AND user_id IS NULL",
                        "SELECT COUNT(*) FROM legacy_posts WHERE user_id IS NULL"),
                new UserIdentityBackfill.Target(
                        "UPDATE table_that_does_not_exist SET user_id=? WHERE username=?",
                        "SELECT 0"));

        assertThrows(RuntimeException.class, () -> backfill(targets).run());
        assertEquals(ROWS_PER_TABLE, jdbc.queryForObject(
                "SELECT COUNT(*) FROM legacy_posts WHERE user_id IS NULL", Long.class));
    }

    private static void createProductionLikeCopy() {
        jdbc.execute("DROP TABLE IF EXISTS snapshot_posts, snapshot_comments, legacy_posts, legacy_comments");
        jdbc.execute("CREATE TABLE legacy_posts (id BIGSERIAL PRIMARY KEY, username VARCHAR(100) NOT NULL, "
                + "user_id VARCHAR(36), payload VARCHAR(100) NOT NULL)");
        jdbc.execute("CREATE TABLE legacy_comments (id BIGSERIAL PRIMARY KEY, username VARCHAR(100) NOT NULL, "
                + "user_id VARCHAR(36), payload VARCHAR(100) NOT NULL)");
        for (String table : List.of("legacy_posts", "legacy_comments")) {
            jdbc.execute("INSERT INTO " + table + " (username, payload) "
                    + "SELECT 'user-' || (g % " + USERS + "), md5(g::text) "
                    + "FROM generate_series(1, " + ROWS_PER_TABLE + ") g");
            jdbc.execute("CREATE INDEX ix_" + table + "_unresolved ON " + table
                    + " (username) WHERE user_id IS NULL");
        }
    }

    private static List<UserIdentityBackfill.Target> targets() {
        return List.of(
                new UserIdentityBackfill.Target(
                        "UPDATE legacy_posts SET user_id=? WHERE username=? AND user_id IS NULL",
                        "SELECT COUNT(*) FROM legacy_posts WHERE user_id IS NULL"),
                new UserIdentityBackfill.Target(
                        "UPDATE legacy_comments SET user_id=? WHERE username=? AND user_id IS NULL",
                        "SELECT COUNT(*) FROM legacy_comments WHERE user_id IS NULL"));
    }

    private static UserIdentityBackfill backfill(List<UserIdentityBackfill.Target> targets) {
        ObjectMapper mapper = new ObjectMapper();
        WorkloadJwtIssuer issuer = new WorkloadJwtIssuer("migration-drill",
                "migration-drill-workload-secret-at-least-32-bytes", Duration.ofSeconds(30), mapper);
        return new UserIdentityBackfill(authBaseUrl, issuer, mapper, jdbc, transactions, targets);
    }

    private static long resolvedRows() {
        return ROWS_PER_TABLE * 2L - unresolvedRows();
    }

    private static long unresolvedRows() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM legacy_posts WHERE user_id IS NULL) + "
                + "(SELECT COUNT(*) FROM legacy_comments WHERE user_id IS NULL)", Long.class);
    }

    private static String checksum(String table) {
        return jdbc.queryForObject("SELECT md5(string_agg(id || ':' || username || ':' || payload, ',' ORDER BY id)) "
                + "FROM " + table, String.class);
    }

    private static String exportBody() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            String id = UUID.nameUUIDFromBytes(("user-" + i).getBytes(StandardCharsets.UTF_8)).toString();
            items.add("{\"rowId\":" + (i + 1) + ",\"userId\":\"" + id
                    + "\",\"username\":\"user-" + i + "\",\"active\":true}");
        }
        return "{\"items\":[" + String.join(",", items) + "],\"checkpoint\":" + USERS
                + ",\"hasMore\":false}";
    }
}
