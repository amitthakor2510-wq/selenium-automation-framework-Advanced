package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailServiceTest {

    private static Map<String, String> cfg(Path dir) {
        Map<String, String> m = new HashMap<>();
        m.put("email.enabled", "true");
        m.put("email.dryRun", "true");
        m.put("email.from", "qa@example.com");
        m.put("email.to", "team@example.com");
        m.put("email.dryRun.dir", dir.resolve("eml").toString());
        m.put("license.out", dir.resolve("lic").toString());
        m.put("email.reports.dir", dir.resolve("none").toString());
        m.put("report.zip.output", dir.resolve("none.zip").toString());
        return m;
    }

    private static RunSummary run(int passed, int failed) {
        return new RunSummary("demoqa", "chrome", "Smoke", passed + failed, passed, failed, 0, 1000,
            Instant.now(), List.of(), "", null);
    }

    private static long emlCount(Path dir) throws Exception {
        Path eml = dir.resolve("eml");
        if (!Files.isDirectory(eml)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(eml)) {
            return s.count();
        }
    }

    @Test
    void disabledSendsNothing(@TempDir Path dir) throws Exception {
        Map<String, String> m = cfg(dir);
        m.put("email.enabled", "false");
        EmailService.Outcome o = EmailService.deliver(EmailSettings.of(m::get, Map.of()), run(1, 0), null, Map.of());
        assertFalse(o.sent());
        assertEquals(0, emlCount(dir));
    }

    @Test
    void sendOnFailureSkipsGreenRunsButSendsRedOnes(@TempDir Path dir) throws Exception {
        Map<String, String> m = cfg(dir);
        m.put("email.sendOn", "failure");
        EmailSettings s = EmailSettings.of(m::get, Map.of());
        assertFalse(EmailService.deliver(s, run(5, 0), null, Map.of()).sent());
        assertEquals(0, emlCount(dir));
        assertTrue(EmailService.deliver(s, run(4, 1), null, Map.of()).sent());
        assertEquals(1, emlCount(dir));
    }

    @Test
    void sendOnNeverNeverSends(@TempDir Path dir) throws Exception {
        Map<String, String> m = cfg(dir);
        m.put("email.sendOn", "never");
        assertFalse(EmailService.deliver(EmailSettings.of(m::get, Map.of()), run(0, 9), null, Map.of()).sent());
    }

    @Test
    void licenseViolationTriggersSendOnFailureEvenWhenTestsPass(@TempDir Path dir) throws Exception {
        Map<String, String> m = cfg(dir);
        m.put("email.sendOn", "failure");
        Files.createDirectories(dir.resolve("lic"));
        Files.writeString(dir.resolve("lic").resolve(LicenseReportWriter.SUMMARY),
            "total=10\nallowed=8\nreview=0\ndenied=2\nmissing=0\nfailing=2\n");
        EmailService.Outcome o = EmailService.deliver(EmailSettings.of(m::get, Map.of()), run(5, 0),
            Instant.now().minusSeconds(60), Map.of());
        assertTrue(o.sent(), o.message());
    }

    @Test
    void staleLicenseSummaryIsIgnored(@TempDir Path dir) throws Exception {
        Map<String, String> m = cfg(dir);
        m.put("email.sendOn", "failure");
        Path summary = dir.resolve("lic").resolve(LicenseReportWriter.SUMMARY);
        Files.createDirectories(summary.getParent());
        Files.writeString(summary, "total=10\ndenied=2\nfailing=2\n");
        Files.setLastModifiedTime(summary,
            java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(7200)));
        EmailService.Outcome o = EmailService.deliver(EmailSettings.of(m::get, Map.of()), run(5, 0),
            Instant.now().minusSeconds(60), Map.of());
        assertFalse(o.sent(), "an old scan must not be reported as part of this run");
    }

    @Test
    void ciLinkComesFromTheEnvironment(@TempDir Path dir) throws Exception {
        EmailService.deliver(EmailSettings.of(cfg(dir)::get, Map.of()), run(1, 0), null,
            Map.of("GITHUB_SERVER_URL", "https://github.com", "GITHUB_REPOSITORY", "me/repo",
                "GITHUB_RUN_ID", "42"));
        Path eml;
        try (Stream<Path> s = Files.list(dir.resolve("eml"))) {
            eml = s.findFirst().orElseThrow();
        }
        assertTrue(Files.readString(eml).contains("github.com/me/repo/actions/runs/42"));
    }

    @Test
    void badSettingsSurfaceAsEmailException(@TempDir Path dir) {
        Map<String, String> m = cfg(dir);
        m.put("email.to", "nonsense");
        assertThrows(EmailException.class,
            () -> EmailService.deliver(EmailSettings.of(m::get, Map.of()), run(1, 0), null, Map.of()));
    }
}
