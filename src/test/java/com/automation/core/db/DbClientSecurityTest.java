package com.automation.core.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attacks on the read-only guard and on secret leakage through reports; H2 does not enforce read-only itself, so the guard is the only defence here. */
class DbClientSecurityTest {

    private static final String URL = "jdbc:h2:mem:dbsecurity;DB_CLOSE_DELAY=-1";

    private DbClient admin;
    private DbClient reader;

    @BeforeEach
    void schema() {
        admin = DbClient.connect(URL, "sa", "", false, 30, 10);
        admin.update("DROP TABLE IF EXISTS users");
        admin.update("DROP TABLE IF EXISTS copy");
        admin.update("CREATE TABLE users (id INT PRIMARY KEY, email VARCHAR(100), status VARCHAR(20), api_key VARCHAR(50), "
            + "update_time VARCHAR(20), delete_flag VARCHAR(5))");
        admin.update("INSERT INTO users VALUES (1, 'ann@example.com', 'ACTIVE', 'sk-live-123456', 't1', 'no')");
        admin.update("INSERT INTO users VALUES (2, 'bob@example.com', 'delete', 'sk-live-789012', 't2', 'no')");
        reader = DbClient.connect(URL, "sa", "");
    }

    @AfterEach
    void close() {
        reader.close();
        admin.close();
    }

    private void assertRefused(String sql) {
        try {
            reader.query(sql);
        } catch (IllegalStateException expected) {
            assertEquals(2L, admin.count("SELECT COUNT(*) FROM users"), "data must be untouched after: " + sql);
            return;
        }
        throw new AssertionError("the read-only guard let this through: " + sql);
    }

    @Test
    void statementsThatWriteThroughASelectAreRefused() {
        assertRefused("SELECT * FROM FINAL TABLE (INSERT INTO users VALUES (9, 'x', 'x', 'k', 't', 'n'))");
        assertRefused("SELECT * FROM OLD TABLE (DELETE FROM users)");
        assertRefused("EXPLAIN ANALYZE DELETE FROM users");
        assertRefused("WITH d AS (DELETE FROM users RETURNING *) SELECT * FROM d");
        assertRefused("SELECT * INTO copy FROM users");
        assertRefused("SELECT * FROM users FOR UPDATE");
        assertRefused("VALUES (1); DELETE FROM users");
        assertRefused("SELECT 1 /* harmless */ ; DROP TABLE users");
        assertRefused("SELECT 1 -- note\n; DELETE FROM users");
    }

    @Test
    void ordinaryQueriesWithRiskyLookingWordsInsideNamesLiteralsAndCommentsStillWork() {
        assertEquals(2, reader.query("SELECT update_time, delete_flag FROM users").size(),
            "column names that merely contain a keyword are fine");
        assertEquals(1, reader.query("SELECT * FROM users WHERE status = 'delete'").size(),
            "a string literal that says 'delete' is just text");
        assertEquals(0, reader.query("SELECT * FROM users WHERE email = 'a;b'").size(), "a ';' inside a literal is just text");
        assertEquals(2, reader.query("SELECT * FROM users -- delete this later\n").size(), "words inside comments are ignored");
        assertEquals(1, reader.query("SELECT * FROM users WHERE status = 'it''s a ''drop'' test' OR id = 1").size());
    }

    @Test
    void rowTextHidesSensitiveColumnsButTheValuesStayAvailableToTheTest() {
        Row row = reader.query("SELECT email, api_key FROM users WHERE id = 1").get(0);
        assertFalse(row.toString().contains("sk-live-123456"), row.toString());
        assertTrue(row.toString().contains("ann@example.com"), row.toString());
        assertEquals("sk-live-123456", row.getString("api_key"), "the test itself still sees the real value");
    }

    @Test
    void failureMessagesNeverCarryASecretFromTheRows() {
        try {
            DbAssertions.assertNoRows(reader, "SELECT email, api_key FROM users WHERE id = ?", 1);
            throw new IllegalStateException("should have failed");
        } catch (AssertionError e) {
            assertFalse(e.getMessage().contains("sk-live-123456"), e.getMessage());
        }
    }

    @Test
    void aHugeParameterIsTruncatedInFailureMessages() {
        String huge = "x".repeat(5000);
        try {
            DbAssertions.assertRowExists(reader, "SELECT * FROM users WHERE email = ?", huge);
            throw new IllegalStateException("should have failed");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().length() < 600, "message length " + e.getMessage().length());
        }
    }

    @Test
    void countOfNonNumericColumnExplainsItselfInsteadOfAClassCastException() {
        try {
            reader.count("SELECT email FROM users WHERE id = 1");
            throw new AssertionError("should have failed");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("not a number"), e.getMessage());
        }
    }

    @Test
    void numericEqualityDoesNotThrowOnNanOrInfinity() {
        assertTrue(Row.valuesEqual(Double.NaN, Double.NaN));
        assertFalse(Row.valuesEqual(Double.POSITIVE_INFINITY, 5));
        assertFalse(Row.valuesEqual(5, null));
        assertTrue(Row.valuesEqual(null, null));
    }

    @Test
    void anOversizedResultIsRefusedInsteadOfLoadedIntoMemory() {
        DbClient small = DbClient.connect(URL, "sa", "", true, 30, 10, 1);
        try {
            small.query("SELECT * FROM users");
            throw new AssertionError("should have refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("db.maxRows"), e.getMessage());
        } finally {
            small.close();
        }
        DbClient exact = DbClient.connect(URL, "sa", "", true, 30, 10, 2);
        assertEquals(2, exact.query("SELECT * FROM users").size(), "exactly maxRows rows is fine");
        exact.close();
        assertEquals(List.of(), List.of());
    }

    @Test
    void credentialsInAConnectionUrlAreNeverShownInAnyForm() {
        assertEquals("jdbc:mysql://app:***@db.internal:3306/qa", DbClient.redact("jdbc:mysql://app:hunter2@db.internal:3306/qa"));
        assertEquals("jdbc:postgresql://h/db?user=a&sslpassword=***&x=1", DbClient.redact("jdbc:postgresql://h/db?user=a&sslpassword=pw&x=1"));
        assertEquals("jdbc:x://h/db?accessToken=***", DbClient.redact("jdbc:x://h/db?accessToken=abc123"));
        assertEquals("jdbc:h2:mem:plain", DbClient.redact("jdbc:h2:mem:plain"));
    }

    @Test
    void aFailedValueAssertionOnASecretColumnDoesNotEchoIt() {
        try {
            DbAssertions.assertValue(reader, "wrong", "SELECT api_key FROM users WHERE id = ?", 1);
            throw new IllegalStateException("should have failed");
        } catch (AssertionError e) {
            assertFalse(e.getMessage().contains("sk-live-123456"), e.getMessage());
            assertFalse(e.getMessage().contains("<wrong>"), e.getMessage());
        }
        try {
            DbAssertions.assertValue(reader, "wrong@example.com", "SELECT email FROM users WHERE id = ?", 1);
            throw new IllegalStateException("should have failed");
        } catch (AssertionError e) {
            assertTrue(e.getMessage().contains("ann@example.com"), "ordinary columns stay readable: " + e.getMessage());
        }
    }
}
