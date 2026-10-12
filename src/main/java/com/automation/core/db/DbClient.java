package com.automation.core.db;

import com.automation.core.config.ConfigReader;
import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Small JDBC helper for checking what the application actually stored - the part a UI or API
 * test cannot see. Plain JDBC, so any database works once its driver is on the test classpath
 * (PostgreSQL is in the pom; add others the same way).
 *
 * <pre>
 * db.url=jdbc:postgresql://localhost:5432/qa_mantis    # blank = no database configured
 * db.user=qa
 * db.password=...                                       # prefer -Ddb.password=... / CI secret
 * db.readOnly=true                                      # default; false enables update()
 * db.queryTimeoutSeconds=30
 * db.connectTimeoutSeconds=10
 * db.maxRows=10000                                      # a query returning more is refused, not loaded
 * </pre>
 * Put them in {@code {site}.properties} for a per-site database.
 *
 * <pre>{@code
 * try (DbClient db = DbClient.connect()) {
 *     Row user = DbAssertions.assertRowExists(db, "SELECT * FROM users WHERE email = ?", email);
 *     assertEquals(user.getString("status"), "ACTIVE");
 * }
 * }</pre>
 *
 * <p>Safety: queries always use bound parameters ({@code ?}), never string concatenation, and the
 * connection is read-only by default (the driver's read-only flag plus a statement guard that
 * refuses anything but a plain SELECT/WITH/SHOW/EXPLAIN/VALUES - see {@link #isReadOnlyStatement}).
 * The guard is a best-effort safety net, NOT a security boundary: SQL dialects have too many ways
 * to write through a query to list them all. The real protection is to connect with a database
 * account that only has SELECT privileges - do that for any database you care about. Passwords
 * are never logged or attached to reports, and rows are masked by column name when printed (see
 * {@link Row#toString}).
 */
public final class DbClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DbClient.class);
    private static final Pattern SECRET_IN_URL = Pattern.compile("(?i)(\\w*(?:password|passwd|pwd|secret|token|key))=[^&;\\s]+");
    private static final Pattern USERINFO_IN_URL = Pattern.compile("(//[^/@:\\s]+):[^@/\\s]+@");

    private final Connection connection;
    private final boolean readOnly;
    private final int queryTimeoutSeconds;
    private final int maxRows;

    static final int DEFAULT_MAX_ROWS = 10_000;

    private DbClient(Connection connection, boolean readOnly, int queryTimeoutSeconds, int maxRows) {
        this.connection = connection;
        this.readOnly = readOnly;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxRows = maxRows;
    }

    /** True when {@code db.url} is set for the active site. */
    public static boolean isConfigured() {
        return !ConfigReader.get("db.url", "").isBlank();
    }

    /** Connects using the {@code db.*} configuration of the active site. */
    public static DbClient connect() {
        String url = ConfigReader.get("db.url", "").trim();
        if (url.isEmpty()) {
            throw new IllegalStateException("No database configured: set db.url (and db.user / db.password) in "
                + "the site's properties file or with -Ddb.url=...");
        }
        return connect(url, ConfigReader.get("db.user", ""), ConfigReader.get("db.password", ""),
            ConfigReader.getBoolean("db.readOnly", true),
            ConfigReader.getInt("db.queryTimeoutSeconds", 30),
            ConfigReader.getInt("db.connectTimeoutSeconds", 10),
            ConfigReader.getInt("db.maxRows", DEFAULT_MAX_ROWS));
    }

    public static DbClient connect(String url, String user, String password, boolean readOnly,
                                   int queryTimeoutSeconds, int connectTimeoutSeconds) {
        return connect(url, user, password, readOnly, queryTimeoutSeconds, connectTimeoutSeconds, DEFAULT_MAX_ROWS);
    }

    public static DbClient connect(String url, String user, String password, boolean readOnly,
                                   int queryTimeoutSeconds, int connectTimeoutSeconds, int maxRows) {
        Connection connection = null;
        try {
            DriverManager.setLoginTimeout(connectTimeoutSeconds);
            connection = user == null || user.isEmpty()
                ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
            connection.setReadOnly(readOnly);
            logger.info("[Db] connected to {} ({})", redact(url), readOnly ? "read-only" : "read-write");
            return new DbClient(connection, readOnly, queryTimeoutSeconds, maxRows);
        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.close(); // do not leak a half-configured connection
                } catch (SQLException ignored) {
                    // the original failure is the one worth reporting
                }
            }
            throw new IllegalStateException("Could not connect to " + redact(url) + ": " + e.getMessage(), e);
        }
    }

    public static DbClient connect(String url, String user, String password) {
        return connect(url, user, password, true, 30, 10);
    }

    /** All rows of a SELECT; {@code params} are bound to the {@code ?} placeholders in order. */
    public List<Row> query(String sql, Object... params) {
        requireAllowed(sql);
        long startedNanos = System.nanoTime();
        try (PreparedStatement statement = prepare(sql, params)) {
            statement.setMaxRows(maxRows + 1); // one more than allowed, so "too many" can be told from "exactly the limit"
            ResultSet rs = statement.executeQuery();
            List<Row> rows = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                if (rows.size() >= maxRows) {
                    throw new IllegalStateException("The query returned more than db.maxRows (" + maxRows
                        + ") rows - add a LIMIT or a tighter WHERE: " + oneLine(sql));
                }
                Row row = new Row();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), normalise(rs.getObject(i)));
                }
                rows.add(row);
            }
            report(sql, rows.size() + " row(s)", startedNanos);
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + oneLine(sql) + " - " + e.getMessage(), e);
        }
    }

    /** The only row, or empty when there is none; more than one row is a mistake in the test's query. */
    public Optional<Row> queryOne(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        if (rows.size() > 1) {
            throw new IllegalStateException("Expected at most one row but got " + rows.size() + " for: " + oneLine(sql));
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** First column of the first row, or null when there is no row. */
    public Object scalar(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0).asMap().values().iterator().next();
    }

    /** First column of the first row as a number (e.g. {@code SELECT COUNT(*) ...}); 0 when there is no row. */
    public long count(String sql, Object... params) {
        Object value = scalar(sql, params);
        if (value != null && !(value instanceof Number)) {
            throw new IllegalStateException("The first column is not a number (" + value.getClass().getSimpleName()
                + ") for: " + oneLine(sql));
        }
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** INSERT/UPDATE/DELETE for test data setup and cleanup. Needs {@code db.readOnly=false}. */
    public int update(String sql, Object... params) {
        if (readOnly) {
            throw new IllegalStateException("This DbClient is read-only. Set db.readOnly=false (only for a test "
                + "database!) to run: " + oneLine(sql));
        }
        long startedNanos = System.nanoTime();
        try (PreparedStatement statement = prepare(sql, params)) {
            int affected = statement.executeUpdate();
            report(sql, affected + " row(s) affected", startedNanos);
            return affected;
        } catch (SQLException e) {
            throw new IllegalStateException("Update failed: " + oneLine(sql) + " - " + e.getMessage(), e);
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warn("[Db] could not close the connection cleanly: {}", e.getMessage());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }

    private void requireAllowed(String sql) {
        if (readOnly && !isReadOnlyStatement(sql)) {
            throw new IllegalStateException("Refusing to run this on a read-only connection (only a plain SELECT / WITH / "
                + "SHOW / EXPLAIN / VALUES is accepted; put values in ? parameters): " + oneLine(sql));
        }
    }

    /** Words that start or hide a write (or a session change) - none may appear outside a literal or comment. */
    private static final Pattern FORBIDDEN_WORDS = Pattern.compile("\\b(insert|update|delete|merge|upsert|truncate|drop|alter|"
        + "create|rename|grant|revoke|call|exec|execute|copy|into|lock|vacuum|analyze|analyse|reindex|set|reset|pragma|"
        + "load|outfile|dumpfile|attach|set_config|setval|nextval|pg_terminate_backend|pg_cancel_backend|pg_reload_conf|"
        + "pg_read_file|pg_read_binary_file|pg_ls_dir|lo_import|lo_export|dblink|dblink_exec|load_file|csvwrite|csvread|"
        + "file_write|file_read|link_schema|xp_cmdshell|openrowset|opendatasource|sp_executesql)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * True for a plain read: after removing string literals, quoted identifiers and comments it must
     * start with SELECT/WITH/SHOW/EXPLAIN/VALUES, contain no second statement, and contain none of
     * the words in {@link #FORBIDDEN_WORDS} - which is what stops {@code SELECT ... FROM OLD TABLE
     * (DELETE ...)}, {@code WITH d AS (DELETE ... RETURNING *) SELECT ...}, {@code EXPLAIN ANALYZE
     * DELETE ...}, {@code SELECT ... INTO ...} and {@code FOR UPDATE}.
     *
     * <p>Every doubt resolves to "refuse": a literal or comment that cannot be closed stays visible,
     * {@code --} only starts a comment before whitespace (MySQL's rule), {@code /*!...*}{@code /}
     * (MySQL executes it) is never stripped, and backslash escapes, {@code #} comments and
     * dollar-quoting are not honoured - so what this method sees can only be MORE than what the
     * database treats as code, never less. A legitimate query that trips it can pass the risky value
     * as a {@code ?} parameter instead.
     */
    static boolean isReadOnlyStatement(String sql) {
        if (sql == null) {
            return false;
        }
        String visible = stripLiteralsAndComments(sql).strip();
        if (visible.endsWith(";")) {
            visible = visible.substring(0, visible.length() - 1).strip(); // one trailing ';' is harmless
        }
        return READ_ONLY_START.matcher(visible).find()
            && visible.indexOf(';') < 0
            && !FORBIDDEN_WORDS.matcher(visible).find();
    }

    private static final Pattern READ_ONLY_START =
        Pattern.compile("^\\(*\\s*(select|with|show|explain|values)\\b", Pattern.CASE_INSENSITIVE);

    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = closingQuote(sql, i, c);
                if (end < 0) {
                    out.append(sql, i, n); // unterminated: leave it visible
                    break;
                }
                out.append(' ');
                i = end + 1;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int eol = sql.indexOf('\n', i);
                out.append(' ');
                i = eol < 0 ? n : eol + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*' && !(i + 2 < n && sql.charAt(i + 2) == '!')) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    out.append(sql, i, n);
                    break;
                }
                out.append(' ');
                i = end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index of the quote that closes the literal opened at {@code open} ('' or "" inside is an escaped quote), or -1. */
    private static int closingQuote(String sql, int open, char quote) {
        int j = open + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j;
            }
            j++;
        }
        return -1;
    }

    /** CLOB/Timestamp-style values become plain Java types the assertions can compare. */
    private static Object normalise(Object value) throws SQLException {
        if (value instanceof java.sql.Clob clob) {
            return clob.getSubString(1, (int) clob.length());
        }
        return value;
    }

    private void report(String sql, String outcome, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
        logger.debug("[Db] {} -> {} in {} ms", oneLine(sql), outcome, millis);
        try {
            if (Allure.getLifecycle().getCurrentTestCaseOrStep().isPresent()) {
                Allure.addAttachment("SQL", "text/plain", sql + System.lineSeparator() + "-- " + outcome
                    + " in " + millis + " ms");
            }
        } catch (RuntimeException e) {
            logger.debug("[Db] could not attach the SQL to the report: {}", e.getMessage());
        }
    }

    static String redact(String url) {
        String masked = USERINFO_IN_URL.matcher(url == null ? "" : url).replaceAll("$1:***@");
        return SECRET_IN_URL.matcher(masked).replaceAll("$1=***");
    }

    static String oneLine(String sql) {
        String line = sql == null ? "" : sql.strip().replaceAll("\\s+", " ");
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }
}
