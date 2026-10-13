package com.automation.core.mail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Sends ONE consolidated report e-mail after a run: reads the per-leg gate files in
 * {@code email.gate.dir} (default {@code target/gate}, written by {@code Scripts/test-gate.sh record}) or,
 * when there are none, every {@code testng-results.xml} under
 * {@code email.results.dir} (default {@code target}), adds the license summary and the report
 * attachments, and mails it with the {@code email.*} settings.
 *
 * <p>Usage: {@code mvn -q exec:java@email-report -Pemail-report -Demail.enabled=true ...} (see the
 * {@code email-report} profile in pom.xml). Typical CI use is a final job that has downloaded the
 * per-site result artifacts. Settings come from {@code -D} properties, {@code config/global.properties}
 * and environment variables (e.g. {@code EMAIL_SMTP_PASSWORD}), in that order.
 *
 * <p>Exit status: 0 on success or when nothing needed sending; 1 when sending failed and
 * {@code email.failOnError=true}.
 */
public final class EmailReportCli {

    private EmailReportCli() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run();
        } catch (Exception e) {
            System.err.println("[EmailReport] Failed: " + e.getMessage());
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run() throws IOException {
        Properties defaults = loadGlobalDefaults();
        EmailSettings settings = EmailSettings.of(
            key -> System.getProperty(key) != null ? System.getProperty(key) : defaults.getProperty(key),
            System.getenv());

        Path resultsDir = Path.of(System.getProperty("email.results.dir", "target"));
        String site = System.getProperty("site", "");
        String browser = System.getProperty("browser", "");
        // Gate files (target/gate/*.txt) cover EVERY leg; testng-results.xml only survives per leg on
        // CI systems that merge artifacts by name. Prefer the gate files when they exist.
        Path gateDir = Path.of(System.getProperty("email.gate.dir", "target/gate"));
        RunSummary summary = GateFileParser.parseDirectory(gateDir, site, browser);
        if (summary == null) {
            summary = TestNgResultsParser.parseDirectory(resultsDir, site, browser);
        }
        if (summary.total() == 0) {
            System.out.println("[EmailReport] No testng-results.xml with tests found under " + resultsDir
                + " - the report will say NO TESTS.");
        }
        try {
            EmailService.Outcome outcome = EmailService.deliver(settings, summary, null, System.getenv());
            System.out.println("[EmailReport] " + outcome.message());
            return 0;
        } catch (EmailException e) {
            System.err.println("[EmailReport] " + e.getMessage());
            return settings.failOnError() ? 1 : 0;
        }
    }

    private static Properties loadGlobalDefaults() throws IOException {
        Properties props = new Properties();
        String explicit = System.getProperty("email.config");
        if (explicit != null && !explicit.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(explicit))) {
                props.load(in);
            }
            return props;
        }
        try (InputStream in = EmailReportCli.class.getClassLoader().getResourceAsStream("config/global.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        return props;
    }
}
