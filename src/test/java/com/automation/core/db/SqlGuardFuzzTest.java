package com.automation.core.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every benign query is accepted, and every benign query with ANY attack fragment added is refused. */
class SqlGuardFuzzTest {

    private static final List<String> BENIGN = List.of(
        "SELECT 1",
        "SELECT a, b FROM t WHERE c = ?",
        "select * from t where name = 'delete; drop table t'",
        "WITH x AS (SELECT 1) SELECT * FROM x",
        "SELECT updated_at, created_at, deleted_flag FROM t",
        "SELECT 1 -- insert something\n",
        "/* drop everything */ SELECT 2",
        "SELECT \"update\", \"delete\" FROM t",
        "VALUES (1), (2)",
        "SELECT 1;",
        "SHOW TABLES",
        "EXPLAIN SELECT * FROM t",
        "(SELECT 1) UNION (SELECT 2)",
        "SELECT * FROM t WHERE note = 'it''s a ''set'' of ''exec'' words'");

    private static final List<String> ATTACKS = List.of(
        "; DELETE FROM t",
        "; DROP TABLE t; --",
        " INTO outfile_t",
        " FOR UPDATE",
        " FROM OLD TABLE (DELETE FROM t)",
        " UNION SELECT set_config('transaction_read_only', 'off', false)",
        " UNION SELECT setval('s', 1)",
        " UNION SELECT pg_terminate_backend(1)",
        " UNION SELECT load_file('/etc/passwd')",
        " UNION SELECT CSVWRITE('x.csv', 'select 1')",
        "/*!50000 DROP TABLE t */",
        "--;DROP TABLE t",
        "\n# ; DELETE FROM t",
        " ; set transaction read write",
        " UNION SELECT dblink_exec('x', 'delete from t')",
        " WITH d AS (DELETE FROM t RETURNING *) SELECT 1",
        " 'unterminated ; DELETE FROM t",
        " /* unterminated ; DELETE FROM t");

    @Test
    void everyBenignQueryIsAccepted() {
        for (String sql : BENIGN) {
            assertTrue(DbClient.isReadOnlyStatement(sql), "wrongly refused: " + sql);
        }
    }

    @Test
    void everyBenignQueryWithAnAttackFragmentAddedIsRefused() {
        for (String benign : BENIGN) {
            for (String attack : ATTACKS) {
                // On a new line: a "-- comment" benign query would otherwise swallow the attack, for the database too.
                assertFalse(DbClient.isReadOnlyStatement(benign + "\n" + attack), "attack let through after: " + benign + " / " + attack);
                if (!attack.contains("unterminated")) { // an unterminated comment/literal is legitimately closed by whatever follows it
                    assertFalse(DbClient.isReadOnlyStatement("SELECT 1 " + attack + "\n" + benign), "attack let through before: " + attack + " / " + benign);
                }
            }
        }
    }

    @Test
    void randomTextNeverMakesTheGuardThrowAndNeverLooksLikeAWriteThatPasses() {
        Random r = new Random(47);
        String alphabet = "abcSELCTdeixupFROMWH;'\"-/*#!\n \t=(),?_0123456789\\$%";
        for (int i = 0; i < 5000; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = r.nextInt(60); n > 0; n--) {
                text.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            String sql = text.toString();
            boolean allowed = DbClient.isReadOnlyStatement(sql); // must not throw
            if (allowed) {
                String visible = DbClient.stripLiteralsAndComments(sql).toLowerCase(java.util.Locale.ROOT);
                assertFalse(visible.matches("(?s).*\\b(insert|update|delete|drop|alter|create|truncate|into|set)\\b.*"),
                    "allowed but a write keyword is visible: " + sql);
                assertFalse(visible.strip().replaceAll(";\\s*$", "").contains(";"), "allowed with a second statement: " + sql);
            }
        }
    }
}
