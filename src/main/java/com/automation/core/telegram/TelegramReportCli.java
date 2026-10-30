package com.automation.core.telegram;

import com.automation.core.mail.GateFileParser;
import com.automation.core.mail.RunSummary;
import com.automation.core.mail.TestNgResultsParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Sends ONE consolidated Telegram report after a run - the counterpart of {@code EmailReportCli}. It
 * reads the per-leg gate files in {@code telegram.gate.dir} (default {@code target/gate}) or, when there
 * are none, every {@code testng-results.xml} under {@code telegram.results.dir} (default {@code target}).
 *
 * <p>Usage: {@code mvn -q test-compile exec:java@telegram-report -Ptelegram-report -Dtelegram.enabled=true}
 * (see the {@code telegram-report} profile in pom.xml). The token and chat id come from the
 * {@code TELEGRAM_BOT_TOKEN} / {@code TELEGRAM_CHAT_ID} environment variables.
 *
 * <p>Exit status: 0 on success or when nothing needed sending; 1 when sending failed and
 * {@code telegram.failOnError=true}.
 */
public final class TelegramReportCli {

    private TelegramReportCli() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run();
        } catch (Exception e) {
            System.err.println("[TelegramReport] Failed: " + e.getMessage());
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run() throws IOException {
        Properties defaults = loadGlobalDefaults();
        TelegramSettings settings = TelegramSettings.of(
            key -> System.getProperty(key) != null ? System.getProperty(key) : defaults.getProperty(key),
            System.getenv());

        Path resultsDir = Path.of(System.getProperty("telegram.results.dir", "target"));
        String site = System.getProperty("site", "");
        String browser = System.getProperty("browser", "");
        Path gateDir = Path.of(System.getProperty("telegram.gate.dir", "target/gate"));
        RunSummary summary = GateFileParser.parseDirectory(gateDir, site, browser);
        if (summary == null) {
            summary = TestNgResultsParser.parseDirectory(resultsDir, site, browser);
        }
        if (summary.total() == 0) {
            System.out.println("[TelegramReport] No testng-results.xml with tests found under " + resultsDir
                + " - the report will say NO TESTS.");
        }
        try {
            TelegramService.Outcome outcome = TelegramService.deliver(settings, summary, null, System.getenv());
            System.out.println("[TelegramReport] " + outcome.message());
            return 0;
        } catch (TelegramException e) {
            System.err.println("[TelegramReport] " + e.getMessage());
            return settings.failOnError() ? 1 : 0;
        }
    }

    private static Properties loadGlobalDefaults() throws IOException {
        Properties props = new Properties();
        String explicit = System.getProperty("telegram.config");
        if (explicit != null && !explicit.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(explicit))) {
                props.load(in);
            }
            return props;
        }
        try (InputStream in = TelegramReportCli.class.getClassLoader()
            .getResourceAsStream("config/global.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        return props;
    }
}
