package com.automation.core.db;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DbClient against a real PostgreSQL started by Testcontainers. DbClientTest runs against H2, which
 * cannot show PostgreSQL's dialect or driver behaviour; this class covers what only the real thing can.
 *
 * <p>Run with {@code ./mvnw -B test-compile failsafe:integration-test@db-it failsafe:verify@db-it -Pdb-integration}.
 * It is an *IT class on purpose: the default build neither compiles nor runs it (no Testcontainers
 * dependency, no Docker needed). Without Docker the tests are skipped, unless
 * {@code -Ddb.it.requireDocker=true} (set in CI) turns that into a failure so a broken runner is not
 * mistaken for a green build. The image is {@code -Ddb.it.postgres.image} (default postgres:16-alpine).
 */
class PostgresDbClientIT {

    private static final String IMAGE = System.getProperty("db.it.postgres.image", "postgres:16-alpine");
    private static final Pattern IMAGE_MAJOR = Pattern.compile("^postgres:(\\d+)");
    private static final UUID ANN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PostgreSQLContainer<?> postgres;

    private DbClient writer;

    @BeforeAll
    static void startPostgres() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            assertFalse(Boolean.getBoolean("db.it.requireDocker"),
                "Docker is required (db.it.requireDocker=true) but is not reachable");
            Assumptions.abort("Docker is not available - skipping the PostgreSQL integration tests");
        }
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE))
            .withDatabaseName("qa")
            .withUsername("qa")
            .withPassword("qa-test-pw");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createSchema() {
        writer = readWrite();
        writer.update("DROP TABLE IF EXISTS users");
        writer.update("CREATE TABLE users (id BIGINT PRIMARY KEY, public_id UUID NOT NULL, email VARCHAR(100), "
            + "status VARCHAR(20), balance NUMERIC(10,2), active BOOLEAN NOT NULL DEFAULT TRUE, profile JSONB, "
            + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        writer.update("INSERT INTO users (id, public_id, email, status, balance, profile) "
            + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))",
            1L, ANN_ID, "ann@example.com", "ACTIVE", new BigDecimal("5.00"), "{\"plan\": \"pro\"}");
        insertUser(writer, 2L, BOB_ID, "bob@example.com", "LOCKED");
    }

    @AfterEach
    void closeConnection() {
        writer.close();
    }

    private static DbClient readWrite() {
        return DbClient.connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), false, 30, 10);
    }

    private static DbClient readOnly() {
        return DbClient.connect(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void insertUser(DbClient db, long id, UUID publicId, String email, String status) {
        db.update("INSERT INTO users (id, public_id, email, status, balance) VALUES (?, ?, ?, ?, ?)",
            id, publicId, email, status, new BigDecimal("0.00"));
    }

    @Test
    void columnTypesComeBackAsUsableJavaTypes() {
        try (DbClient reader = readOnly()) {
            Row row = reader.queryOne("SELECT * FROM users WHERE id = ?", 1L).orElseThrow();
            assertEquals(1L, row.getLong("id"));
            assertEquals(ANN_ID, row.get("public_id"));
            assertEquals(Boolean.TRUE, row.get("active"));
            assertInstanceOf(BigDecimal.class, row.get("balance"));
            assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) row.get("balance")));
            assertInstanceOf(java.util.Date.class, row.get("created_at"));
            assertTrue(row.getString("profile").contains("\"plan\""), row.getString("profile"));
        }
    }

    @Test
    void columnNamesAreLowerCasedEvenWhenTheQueryQuotesMixedCase() {
        try (DbClient reader = readOnly()) {
            Row row = reader.queryOne("SELECT email AS \"EmailAddress\" FROM users WHERE id = ?", 1L).orElseThrow();
            assertTrue(row.has("emailaddress"));
            assertEquals("ann@example.com", row.getString("EmailAddress"));
        }
    }

    @Test
    void uuidParameterNeedsTheUuidTypeOrAnExplicitCast() {
        try (DbClient reader = readOnly()) {
            // setObject(String) is sent as varchar and PostgreSQL has no uuid = varchar operator.
            assertThrows(IllegalStateException.class,
                () -> reader.query("SELECT * FROM users WHERE public_id = ?", ANN_ID.toString()));
            assertEquals(1, reader.query("SELECT * FROM users WHERE public_id = CAST(? AS uuid)", ANN_ID.toString()).size());
            assertEquals(1, reader.query("SELECT * FROM users WHERE public_id = ?", ANN_ID).size());
        }
    }

    @Test
    void readOnlyClientRefusesPostgresWritesAndTheGuardIsWhatStopsThem() {
        String[] writes = {
            "WITH gone AS (DELETE FROM users WHERE id = 2 RETURNING *) SELECT * FROM gone",
            "SELECT * FROM users WHERE id = 1 FOR UPDATE",
            "SELECT set_config('default_transaction_read_only', 'off', false)",
            "SELECT * INTO users_copy FROM users",
            "EXPLAIN ANALYZE DELETE FROM users WHERE id = 2",
            "SELECT pg_terminate_backend(pg_backend_pid())",
            "COPY users TO PROGRAM 'id'",
            "SELECT 1; DELETE FROM users"
        };
        try (DbClient reader = readOnly()) {
            for (String sql : writes) {
                assertThrows(IllegalStateException.class, () -> reader.query(sql), sql);
            }
        }
        assertEquals(2L, writer.count("SELECT COUNT(*) FROM users"), "no row was removed");
        assertEquals(0L, writer.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'users_copy'"));

        // Control: the writable CTE really deletes when nothing guards it (read-write client),
        // so the refusals above come from DbClient, not from the database.
        assertEquals(1, writer.query(writes[0]).size());
        assertEquals(1L, writer.count("SELECT COUNT(*) FROM users"));
    }

    @Test
    void readOnlyClientRefusesQuotingThatHidesASecondStatement() {
        // Both pass a quote-stripping guard that only understands standard SQL, and both delete rows
        // on PostgreSQL 16 (E'' honours backslash escapes; $$ quoting makes the inner quote plain text).
        String[] disguised = {
            "SELECT E'\\''; DELETE FROM users; --'",
            "SELECT $$'$$, 'x' ; DELETE FROM users; --'"
        };
        try (DbClient reader = readOnly()) {
            for (String sql : disguised) {
                assertThrows(IllegalStateException.class, () -> reader.query(sql), sql);
            }
        }
        assertEquals(2L, writer.count("SELECT COUNT(*) FROM users"), "no row was removed");
    }

    @Test
    void readOnlyClientStillAcceptsPlainPostgresReads() {
        try (DbClient reader = readOnly()) {
            assertEquals(2, reader.query("SELECT * FROM users ORDER BY id").size());
            assertEquals(2, reader.query("WITH x AS (SELECT * FROM users) SELECT * FROM x").size());
            assertFalse(reader.query("SHOW server_version").isEmpty());
            assertFalse(reader.query("EXPLAIN SELECT * FROM users").isEmpty());
            assertEquals(1, reader.query("SELECT * FROM users WHERE profile ->> 'plan' = ?", "pro").size());
        }
    }

    @Test
    void queryTimeoutCancelsASlowStatement() {
        try (DbClient impatient = DbClient.connect(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword(), true, 1, 10)) {
            long started = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> impatient.query("SELECT pg_sleep(10)"));
            long millis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(millis < 8_000, "the statement was cancelled after about 1 s, not run to completion: " + millis + " ms");
            assertTrue(failure.getMessage().startsWith("Query failed"), failure.getMessage());
        }
    }

    @Test
    void maxRowsRefusesAnOversizedResultButAcceptsExactlyTheLimit() {
        try (DbClient limited = DbClient.connect(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword(), true, 30, 10, 5)) {
            assertEquals(5, limited.query("SELECT generate_series(1, 5)").size());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> limited.query("SELECT generate_series(1, 50)"));
            assertTrue(failure.getMessage().contains("db.maxRows"), failure.getMessage());
        }
    }

    @Test
    void assertionsWorkOnNumericColumns() {
        DbAssertions.assertRowCount(writer, 2, "SELECT * FROM users");
        DbAssertions.assertNoRows(writer, "SELECT * FROM users WHERE status = ?", "DELETED");
        DbAssertions.assertValue(writer, 5, "SELECT balance FROM users WHERE id = ?", 1L);
        Row row = DbAssertions.assertRowExists(writer, "SELECT * FROM users WHERE email = ?", "bob@example.com");
        assertEquals("LOCKED", row.getString("status"));
    }

    @Test
    void awaitRowSeesARowCommittedByAnotherConnection() throws InterruptedException {
        Thread other = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try (DbClient second = readWrite()) {
                insertUser(second, 3L, UUID.randomUUID(), "late@example.com", "ACTIVE");
            }
        });
        other.start();
        Row row = DbAssertions.awaitRow(writer, 5_000, "SELECT * FROM users WHERE id = ?", 3L);
        assertEquals("late@example.com", row.getString("email"));
        other.join();
    }

    @Test
    void aFailedLoginDoesNotLeakThePassword() {
        String wrong = "definitely-wrong-pw-9271";
        IllegalStateException byArgument = assertThrows(IllegalStateException.class,
            () -> DbClient.connect(postgres.getJdbcUrl(), postgres.getUsername(), wrong));
        assertFalse(String.valueOf(byArgument.getMessage()).contains(wrong), byArgument.getMessage());

        String separator = postgres.getJdbcUrl().contains("?") ? "&" : "?";
        String urlWithSecret = postgres.getJdbcUrl() + separator + "user=" + postgres.getUsername() + "&password=" + wrong;
        IllegalStateException byUrl = assertThrows(IllegalStateException.class,
            () -> DbClient.connect(urlWithSecret, "", ""));
        assertFalse(String.valueOf(byUrl.getMessage()).contains(wrong), byUrl.getMessage());
    }

    @Test
    void serverAndDriverVersionsMatchWhatTheBuildPins() throws SQLException {
        Matcher major = IMAGE_MAJOR.matcher(IMAGE);
        if (major.find()) {
            String serverVersion = String.valueOf(writer.scalar("SHOW server_version"));
            assertTrue(serverVersion.startsWith(major.group(1)), "server " + serverVersion + " vs image " + IMAGE);
        }
        int driverMajor = DriverManager.getDriver(postgres.getJdbcUrl()).getMajorVersion();
        assertTrue(driverMajor >= 42, "unexpected PostgreSQL JDBC driver major version " + driverMajor);
        List<Row> rows = writer.query("SELECT 1 AS one");
        assertEquals(1L, rows.get(0).getLong("one"));
    }
}
