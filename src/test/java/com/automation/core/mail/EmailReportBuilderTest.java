package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailReportBuilderTest {

    private static RunSummary summary(int passed, int failed, int skipped, List<RunSummary.FailedTest> failures,
                                      String ciLink, LicenseReportWriter.Summary license) {
        return new RunSummary("demoqa", "chrome", "DemoQA Smoke", passed + failed + skipped, passed, failed, skipped,
            125_000, Instant.now(), failures, ciLink, license);
    }

    @Test
    void subjectTemplateIsFilled() {
        RunSummary s = summary(8, 2, 1, List.of(), "", null);
        assertEquals("[FAILED] demoqa DemoQA Smoke - 8/11 passed",
            EmailReportBuilder.subject("[{status}] {site} {suite} - {passed}/{total} passed", s));
        assertEquals("chrome 2 1", EmailReportBuilder.subject("{browser} {failed} {skipped}", s));
    }

    @Test
    void subjectCannotInjectHeaders() {
        RunSummary s = new RunSummary("a\r\nBcc: x@evil.test", "", "suite\nSubject: spoof", 1, 1, 0, 0, 0,
            Instant.now(), List.of(), "", null);
        String subject = EmailReportBuilder.subject("{site} {suite}", s);
        assertFalse(subject.contains("\r"));
        assertFalse(subject.contains("\n"));
    }

    @Test
    void statusReflectsOutcome() {
        assertEquals("PASSED", summary(3, 0, 1, List.of(), "", null).status());
        assertEquals("FAILED", summary(3, 1, 0, List.of(), "", null).status());
        assertEquals("NO TESTS", summary(0, 0, 0, List.of(), "", null).status());
    }

    @Test
    void htmlEscapesTestNamesAndMessages() {
        RunSummary s = summary(0, 1, 0, List.of(new RunSummary.FailedTest("<img src=x onerror=alert(1)>",
            "Cls<b>", "expected <script>alert(1)</script> & \"quotes\"")), "", null);
        String html = EmailReportBuilder.html(s, List.of("note <i>x</i>"));
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img src=x"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;"));
        assertTrue(html.contains("note &lt;i&gt;x&lt;/i&gt;"));
    }

    @Test
    void onlyHttpLinksBecomeLinks() {
        String safe = EmailReportBuilder.html(summary(1, 0, 0, List.of(), "https://ci.example.com/run/1", null),
            List.of());
        assertTrue(safe.contains("<a href=\"https://ci.example.com/run/1\">"));
        String evil = EmailReportBuilder.html(summary(1, 0, 0, List.of(), "javascript:alert(1)", null), List.of());
        assertFalse(evil.contains("javascript:"), evil);
        String text = EmailReportBuilder.text(summary(1, 0, 0, List.of(), "javascript:alert(1)", null), List.of());
        assertFalse(text.contains("javascript:"));
    }

    @Test
    void messagesAreRedactedAndShortened() {
        assertEquals("login failed password=**** for user",
            EmailReportBuilder.sanitizeMessage("login failed password=hunter2 for user"));
        assertTrue(EmailReportBuilder.sanitizeMessage("api_key: abc123").endsWith("****"));
        assertTrue(EmailReportBuilder.sanitizeMessage("Authorization: Bearer abcdefghijklmnop").contains("****"));
        assertFalse(EmailReportBuilder.sanitizeMessage("Authorization: Bearer abcdefghijklmnop")
            .contains("abcdefghijklmnop"));
        assertFalse(EmailReportBuilder.sanitizeMessage("TOKEN=zzz999").contains("zzz999"));
        // Ordinary words are left alone.
        assertEquals("Invalid password provided", EmailReportBuilder.sanitizeMessage("Invalid password provided"));
        String longMsg = "x".repeat(1000);
        assertEquals(303, EmailReportBuilder.sanitizeMessage(longMsg).length());
        assertEquals("", EmailReportBuilder.sanitizeMessage(null));
        assertEquals("a b c", EmailReportBuilder.sanitizeMessage("a\n  b\r\n\tc"));
    }

    @Test
    void longFailureListsAreCapped() {
        List<RunSummary.FailedTest> many = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            many.add(new RunSummary.FailedTest("t" + i, "C", "m"));
        }
        RunSummary s = summary(0, 35, 0, many, "", null);
        assertTrue(EmailReportBuilder.html(s, List.of()).contains("and 15 more"));
        assertTrue(EmailReportBuilder.text(s, List.of()).contains("and 15 more"));
        assertFalse(EmailReportBuilder.text(s, List.of()).contains("t34"));
    }

    @Test
    void licenseSummaryAppearsAndFailuresAreFlagged() {
        LicenseReportWriter.Summary lic = new LicenseReportWriter.Summary(120, 110, 8, 1, 1, 1);
        RunSummary s = summary(5, 0, 0, List.of(), "", lic);
        String text = EmailReportBuilder.text(s, List.of());
        assertTrue(text.contains("120 dependencies"), text);
        assertTrue(text.contains("1 denied"));
        assertTrue(EmailReportBuilder.html(s, List.of()).contains("#b00020"), "failing licenses are highlighted");
        assertTrue(s.hasProblems(), "a license violation counts as a problem even when every test passed");
        assertFalse(summary(5, 0, 0, List.of(), "", null).hasProblems());
    }

    @Test
    void durationFormats() {
        assertEquals("45s", EmailReportBuilder.duration(45_000));
        assertEquals("2m 5s", EmailReportBuilder.duration(125_000));
        assertEquals("1h 1m 1s", EmailReportBuilder.duration(3_661_000));
    }
}
