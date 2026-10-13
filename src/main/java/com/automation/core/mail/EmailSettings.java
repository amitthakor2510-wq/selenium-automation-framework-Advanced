package com.automation.core.mail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * E-mail report configuration. Every key can come from (in order) a {@code -D} system property,
 * the properties files ({@code global.properties} / the site file), or an environment variable named
 * after the key ({@code email.smtp.password} -> {@code EMAIL_SMTP_PASSWORD}) - so CI secrets never need
 * to be written into a file. The password has no default, is never logged and is masked in
 * {@link #toString()}.
 */
public final class EmailSettings {

    /** How the SMTP connection is secured. */
    public enum Security { STARTTLS, SSL, NONE }

    /** When a report is sent. */
    public enum SendOn { ALWAYS, FAILURE, NEVER }

    private static final String LOOPBACK = "localhost";

    private final boolean enabled;
    private final boolean dryRun;
    private final String host;
    private final int port;
    private final Security security;
    private final String username;
    private final String password;
    private final String from;
    private final String to;
    private final String cc;
    private final String bcc;
    private final String subjectTemplate;
    private final SendOn sendOn;
    private final boolean attachReports;
    private final boolean attachLicenseReport;
    private final long maxAttachmentBytes;
    private final int timeoutMillis;
    private final boolean failOnError;
    private final Path dryRunDir;
    private final Path extentDir;
    private final Path zipFile;
    private final Path licenseDir;

    private EmailSettings(UnaryOperator<String> lookup, Map<String, String> env) {
        this.enabled = bool(lookup, env, "email.enabled", false);
        this.dryRun = bool(lookup, env, "email.dryRun", false);
        this.host = str(lookup, env, "email.smtp.host", "");
        this.port = integer(lookup, env, "email.smtp.port", 587);
        this.security = security(str(lookup, env, "email.smtp.security", "starttls"));
        this.username = str(lookup, env, "email.smtp.username", "");
        this.password = str(lookup, env, "email.smtp.password", "");
        this.from = str(lookup, env, "email.from", "");
        this.to = str(lookup, env, "email.to", "");
        this.cc = str(lookup, env, "email.cc", "");
        this.bcc = str(lookup, env, "email.bcc", "");
        this.subjectTemplate = str(lookup, env, "email.subject",
            "[{status}] {site} {suite} - {passed}/{total} passed");
        this.sendOn = sendOn(str(lookup, env, "email.sendOn", "always"));
        this.attachReports = bool(lookup, env, "email.attach.reports", true);
        this.attachLicenseReport = bool(lookup, env, "email.attach.licenseReport", true);
        this.maxAttachmentBytes = Math.max(0, integer(lookup, env, "email.attach.maxTotalMb", 10)) * 1024L * 1024L;
        this.timeoutMillis = Math.max(1000, integer(lookup, env, "email.timeoutMs", 20000));
        this.failOnError = bool(lookup, env, "email.failOnError", false);
        this.dryRunDir = Path.of(str(lookup, env, "email.dryRun.dir", "target/email"));
        this.extentDir = Path.of(str(lookup, env, "email.reports.dir", "target/extent-reports"));
        this.zipFile = Path.of(str(lookup, env, "report.zip.output", "target/report-archives/extent-reports.zip"));
        this.licenseDir = Path.of(str(lookup, env, "license.out", "target/license-report"));
    }

    /** {@code lookup} returns a configured value or null/blank; {@code env} is the environment. */
    public static EmailSettings of(UnaryOperator<String> lookup, Map<String, String> env) {
        return new EmailSettings(lookup, env);
    }

    private static String str(UnaryOperator<String> lookup, Map<String, String> env, String key, String dflt) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            v = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private static boolean bool(UnaryOperator<String> lookup, Map<String, String> env, String key, boolean dflt) {
        return Boolean.parseBoolean(str(lookup, env, key, String.valueOf(dflt)));
    }

    private static int integer(UnaryOperator<String> lookup, Map<String, String> env, String key, int dflt) {
        try {
            return Integer.parseInt(str(lookup, env, key, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Security security(String raw) {
        try {
            return Security.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Security.STARTTLS;
        }
    }

    private static SendOn sendOn(String raw) {
        try {
            return SendOn.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SendOn.ALWAYS;
        }
    }

    /**
     * Problems that would make sending impossible or unsafe; empty = ready. Dry-run needs only the
     * addresses (nothing is sent), a real send needs the server too.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (from.isBlank()) {
            out.add("email.from is not set");
        } else if (!EmailAddresses.isValid(from)) {
            out.add("email.from is not a valid address");
        }
        if (to.isBlank()) {
            out.add("email.to is not set");
        } else if (!EmailAddresses.isValidList(to)) {
            out.add("email.to contains an invalid address");
        }
        if (!cc.isBlank() && !EmailAddresses.isValidList(cc)) {
            out.add("email.cc contains an invalid address");
        }
        if (!bcc.isBlank() && !EmailAddresses.isValidList(bcc)) {
            out.add("email.bcc contains an invalid address");
        }
        if (!dryRun) {
            if (host.isBlank()) {
                out.add("email.smtp.host is not set");
            }
            if (port < 1 || port > 65535) {
                out.add("email.smtp.port must be 1-65535");
            }
            if (!username.isBlank() && password.isBlank()) {
                out.add("email.smtp.username is set but no password (use the EMAIL_SMTP_PASSWORD environment variable)");
            }
            if (!username.isBlank() && security == Security.NONE && !isLoopback(host)) {
                out.add("refusing to send credentials over an unencrypted connection "
                    + "(set email.smtp.security=starttls or ssl)");
            }
        }
        return out;
    }

    private static boolean isLoopback(String h) {
        return LOOPBACK.equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "::1".equals(h);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public Security security() {
        return security;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String cc() {
        return cc;
    }

    public String bcc() {
        return bcc;
    }

    public String subjectTemplate() {
        return subjectTemplate;
    }

    public SendOn sendOn() {
        return sendOn;
    }

    public boolean attachReports() {
        return attachReports;
    }

    public boolean attachLicenseReport() {
        return attachLicenseReport;
    }

    public long maxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public boolean failOnError() {
        return failOnError;
    }

    public Path dryRunDir() {
        return dryRunDir;
    }

    public Path extentDir() {
        return extentDir;
    }

    public Path zipFile() {
        return zipFile;
    }

    public Path licenseDir() {
        return licenseDir;
    }

    @Override
    public String toString() {
        return "EmailSettings[enabled=" + enabled + ", dryRun=" + dryRun + ", host=" + host + ":" + port
            + ", security=" + security + ", username=" + (username.isBlank() ? "" : username)
            + ", password=" + (password.isBlank() ? "(none)" : "****") + ", from=" + from + ", to=" + to
            + ", sendOn=" + sendOn + "]";
    }
}
