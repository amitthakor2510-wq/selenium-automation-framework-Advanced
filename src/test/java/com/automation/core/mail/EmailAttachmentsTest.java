package com.automation.core.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAttachmentsTest {

    private static EmailSettings settings(Path dir, Map<String, String> extra) {
        Map<String, String> m = new HashMap<>();
        m.put("email.reports.dir", dir.resolve("extent").toString());
        m.put("report.zip.output", dir.resolve("zip/all.zip").toString());
        m.put("license.out", dir.resolve("lic").toString());
        m.putAll(extra);
        return EmailSettings.of(m::get, Map.of());
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void zipIsPreferredOverIndividualReports(@TempDir Path dir) throws Exception {
        write(dir.resolve("zip/all.zip"), "zipdata");
        write(dir.resolve("extent/demoqa/chrome/smoke/index.html"), "<html/>");
        EmailAttachments a = EmailAttachments.collect(settings(dir, Map.of()), Instant.now().minusSeconds(60));
        assertEquals(1, a.items().size());
        assertEquals("all.zip", a.items().get(0).name());
    }

    @Test
    void individualReportsGetFlattenedUniqueNames(@TempDir Path dir) throws Exception {
        write(dir.resolve("extent/demoqa/chrome/smoke/index.html"), "<html/>");
        write(dir.resolve("extent/demoqa/firefox/smoke/index.html"), "<html/>");
        EmailAttachments a = EmailAttachments.collect(settings(dir, Map.of()), null);
        assertEquals(List.of("demoqa-chrome-smoke-index.html", "demoqa-firefox-smoke-index.html"),
            a.items().stream().map(EmailAttachments.Item::name).toList());
    }

    @Test
    void staleFilesFromAnEarlierRunAreNotSent(@TempDir Path dir) throws Exception {
        Path old = write(dir.resolve("extent/demoqa/chrome/smoke/index.html"), "<html/>");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(3600)));
        Path fresh = write(dir.resolve("extent/saucedemo/chrome/smoke/index.html"), "<html/>");
        EmailAttachments a = EmailAttachments.collect(settings(dir, Map.of()), Instant.now().minusSeconds(30));
        assertEquals(1, a.items().size());
        assertEquals(fresh, a.items().get(0).file());
    }

    @Test
    void sizeCapDropsFilesAndSaysSo(@TempDir Path dir) throws Exception {
        write(dir.resolve("extent/a/index.html"), "x".repeat(600 * 1024));
        write(dir.resolve("extent/b/index.html"), "x".repeat(600 * 1024));
        // 1 MB cap: the first fits, the second does not.
        EmailAttachments a = EmailAttachments.collect(settings(dir, Map.of("email.attach.maxTotalMb", "1")), null);
        assertEquals(1, a.items().size());
        assertEquals(1, a.notes().size());
        assertTrue(a.notes().get(0).contains("b-index.html"), a.notes().toString());
    }

    @Test
    void licenseReportIsAttachedAndTogglesOff(@TempDir Path dir) throws Exception {
        write(dir.resolve("lic/license-report.html"), "<html/>");
        EmailAttachments on = EmailAttachments.collect(settings(dir, Map.of()), null);
        assertEquals("license-report.html", on.items().get(0).name());
        EmailAttachments off = EmailAttachments.collect(
            settings(dir, Map.of("email.attach.licenseReport", "false")), null);
        assertTrue(off.items().isEmpty());
        EmailAttachments none = EmailAttachments.collect(
            settings(dir, Map.of("email.attach.reports", "false", "email.attach.licenseReport", "false")), null);
        assertTrue(none.items().isEmpty());
    }

    @Test
    void missingDirectoriesAreFine(@TempDir Path dir) {
        EmailAttachments a = EmailAttachments.collect(settings(dir, Map.of()), null);
        assertTrue(a.items().isEmpty());
        assertTrue(a.notes().isEmpty());
    }
}
