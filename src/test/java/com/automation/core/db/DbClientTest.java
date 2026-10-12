package com.automation.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against an in-memory H2 database - no server, no configuration. */
class DbClientTest {

    private static final String URL = "jdbc:h2:mem:dbclienttest;DB_CLOSE_DELAY=-1";

    private DbClient writer;

    @BeforeEach
    void createSchema() {
        writer = DbClient.connect(URL, "sa", "", false, 30, 10);
        writer.update("DROP TABLE IF EXISTS users");
        writer.update("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(100), status VARCHAR(20), balance DECIMAL(10,2))");
        writer.update("INSERT INTO users VALUES (?, ?, ?, ?)", 1, "ann@example.com", "ACTIVE", new java.math.BigDecimal("5.00"));
        writer.update("INSERT INTO users VALUES (?, ?, ?, ?)", 2, "bob@example.com", "LOCKED", new java.math.BigDecimal("0.00"));
    }

    @AfterEach
    void closeConnection() {
        writer.close();
    }

    private static void assertFailsWith(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            assertTrue(type.isInstance(t), "expected " + type.getSimpleName() + " but got " + t);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    @Test
    void queryBindsParametersAndLowerCasesColumnNames() {
        DbClient reader = DbClient.connect(URL, "sa", "");
        List<Row> rows = reader.query("SELECT ID, EMAIL FROM users WHERE status = ?", "ACTIVE");
        assertEquals(1, rows.size());
        assertEquals("ann@example.com", rows.get(0).getString("email"));
        assertEquals(Long.valueOf(1), rows.get(0).getLong("Id"), "column lookup ignores case");
        assertFalse(rows.get(0).has("nope"));
        reader.close();
    }

    @Test
    void injectionAttemptInAParameterIsJustAValue() {
        DbClient reader = DbClient.connect(URL, "sa", "");
        assertEquals(0, reader.query("SELECT * FROM users WHERE email = ?", "x' OR '1'='1").size());
        reader.close();
    }

    @Test
    void scalarCountAndQueryOne() {
        assertEquals(2L, writer.count("SELECT COUNT(*) FROM users"));
        assertEquals(0L, writer.count("SELECT COUNT(*) FROM users WHERE status = ?", "NONE"));
        assertEquals("ann@example.com", writer.scalar("SELECT email FROM users WHERE id = ?", 1));
        assertEquals(null, writer.scalar("SELECT email FROM users WHERE id = ?", 99));
        assertTrue(writer.queryOne("SELECT * FROM users WHERE id = ?", 2).isPresent());
        assertFalse(writer.queryOne("SELECT * FROM users WHERE id = ?", 99).isPresent());
        assertFailsWith(IllegalStateException.class, () -> writer.queryOne("SELECT * FROM users"));
    }

    @Test
    void readOnlyClientRefusesAnythingButSelect() {
        DbClient reader = DbClient.connect(URL, "sa", "");
        assertTrue(reader.isReadOnly());
        assertFailsWith(IllegalStateException.class, () -> reader.update("DELETE FROM users"));
        assertFailsWith(IllegalStateException.class, () -> reader.query("DELETE FROM users"));
        assertFailsWith(IllegalStateException.class, () -> reader.query("SELECT 1; DROP TABLE users"));
        assertEquals(2L, writer.count("SELECT COUNT(*) FROM users"), "nothing was deleted");
        reader.close();
    }

    @Test
    void statementGuardRecognisesReadOnlyStatements() {
        assertTrue(DbClient.isReadOnlyStatement("select 1"));
        assertTrue(DbClient.isReadOnlyStatement("  \n -- leading comment\n WITH x AS (SELECT 1) SELECT * FROM x"));
        assertTrue(DbClient.isReadOnlyStatement("/* c */ SELECT 1"));
        assertFalse(DbClient.isReadOnlyStatement("DELETE FROM users"));
        assertFalse(DbClient.isReadOnlyStatement("-- select\nUPDATE users SET status = 'x'"));
        assertFalse(DbClient.isReadOnlyStatement("selectfoo"));
        assertFalse(DbClient.isReadOnlyStatement(null));
    }

    @Test
    void secretsAreMaskedInLoggedUrls() {
        assertEquals("jdbc:postgresql://h/db?user=a&password=***", DbClient.redact("jdbc:postgresql://h/db?user=a&password=hunter2"));
        assertEquals("jdbc:h2:mem:x", DbClient.redact("jdbc:h2:mem:x"));
    }

    @Test
    void assertionsPassOnMatchingDataAndFailWithAUsefulMessage() {
        DbAssertions.assertRowCount(writer, 2, "SELECT * FROM users");
        DbAssertions.assertNoRows(writer, "SELECT * FROM users WHERE status = ?", "DELETED");
        DbAssertions.assertValue(writer, 5, "SELECT balance FROM users WHERE id = ?", 1);
        Row row = DbAssertions.assertRowExists(writer, "SELECT * FROM users WHERE email = ?", "bob@example.com");
        assertEquals("LOCKED", row.getString("status"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("status", "ACTIVE");
        expected.put("balance", 5);
        DbAssertions.assertRow(writer, expected, "SELECT * FROM users WHERE id = ?", 1);

        expected.put("status", "LOCKED");
        try {
            DbAssertions.assertRow(writer, expected, "SELECT * FROM users WHERE id = ?", 1);
            throw new IllegalStateException("should have failed");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().contains("column 'status': expected <LOCKED> but was <ACTIVE>"), e.getMessage());
            assertTrue(e.getMessage().contains("params=[1]"), e.getMessage());
        }
    }

    @Test
    void awaitRowSeesADelayedWriteAndTimesOutWhenNothingArrives() throws Exception {
        Thread writerThread = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            DbClient other = DbClient.connect(URL, "sa", "", false, 30, 10);
            other.update("INSERT INTO users VALUES (?, ?, ?, ?)", 3, "late@example.com", "ACTIVE", new java.math.BigDecimal("1.00"));
            other.close();
        });
        writerThread.start();
        Row row = DbAssertions.awaitRow(writer, 5000, "SELECT * FROM users WHERE id = ?", 3);
        assertEquals("late@example.com", row.getString("email"));
        writerThread.join();

        try {
            DbAssertions.awaitRow(writer, 300, "SELECT * FROM users WHERE id = ?", 404);
            throw new IllegalStateException("should have timed out");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().contains("No row appeared within 300 ms"), e.getMessage());
        }
        DbAssertions.awaitRowCount(writer, 3, 1000, "SELECT * FROM users");
    }
}
