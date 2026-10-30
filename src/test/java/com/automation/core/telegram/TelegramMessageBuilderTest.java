package com.automation.core.telegram;

import com.automation.core.mail.RunSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageBuilderTest {

    private static RunSummary run(int passed, int failed, List<RunSummary.FailedTest> failures, String link) {
        return new RunSummary("demoqa", "chrome", "Smoke", passed + failed, passed, failed, 0, 65_000,
            Instant.now(), failures, link, null);
    }

    @Test
    void summaryShowsStatusCountsAndDuration() {
        String m = TelegramMessageBuilder.message(run(9, 1, List.of(), ""), 10, List.of());
        assertTrue(m.contains("<b>FAILED</b>"));
        assertTrue(m.contains("demoqa / chrome"));
        assertTrue(m.contains("Passed 9"));
        assertTrue(m.contains("Failed 1"));
        assertTrue(m.contains("1m 5s"));
    }

    @Test
    void everyDynamicValueIsEscaped() {
        List<RunSummary.FailedTest> f = List.of(new RunSummary.FailedTest("t<1>", "A&B", "expected <b> but \"x\""));
        String m = TelegramMessageBuilder.message(run(0, 1, f, ""), 10, List.of("careful <i>"));
        assertTrue(m.contains("A&amp;B.t&lt;1&gt;"));
        assertTrue(m.contains("expected &lt;b&gt;"));
        assertTrue(m.contains("careful &lt;i&gt;"));
        assertFalse(m.contains("<i>"));
    }

    @Test
    void ciLinkIsOnlyRenderedForHttpUrls() {
        assertTrue(TelegramMessageBuilder.message(run(1, 0, List.of(), "https://ci.example/run/1"), 10, List.of())
            .contains("<a href=\"https://ci.example/run/1\">Open CI run</a>"));
        assertFalse(TelegramMessageBuilder.message(run(1, 0, List.of(), "javascript:alert(1)"), 10, List.of())
            .contains("<a href"));
    }

    @Test
    void attributeQuotesInTheLinkCannotBreakOutOfTheHref() {
        String m = TelegramMessageBuilder.message(run(1, 0, List.of(), "https://x.example/?a=\"onclick=1"), 10,
            List.of());
        assertFalse(m.contains("\"onclick"));
        assertTrue(m.contains("&quot;onclick=1"));
    }

    @Test
    void failureListIsCappedAndTheRestIsCounted() {
        List<RunSummary.FailedTest> f = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            f.add(new RunSummary.FailedTest("t" + i, "C", "boom"));
        }
        String m = TelegramMessageBuilder.message(run(0, 25, f, ""), 10, List.of());
        assertTrue(m.contains("C.t9"));
        assertFalse(m.contains("C.t10:"));
        assertTrue(m.contains("... and 15 more"));
    }

    @Test
    void neverExceedsTelegramsLimitEvenWithHugeMessages() {
        List<RunSummary.FailedTest> f = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            f.add(new RunSummary.FailedTest("test" + i, "Class", "x".repeat(500)));
        }
        String m = TelegramMessageBuilder.message(run(0, 100, f, "https://ci.example/1"), 100, List.of());
        assertTrue(m.length() <= TelegramMessageBuilder.MAX_MESSAGE_CHARS, "length " + m.length());
        assertTrue(m.contains("more"));
    }

    @Test
    void noTestsIsNotReportedAsAPass() {
        RunSummary empty = new RunSummary("", "", "", 0, 0, 0, 0, 0, Instant.now(), List.of(), "", null);
        assertTrue(TelegramMessageBuilder.message(empty, 10, List.of()).contains("NO TESTS"));
    }

    @Test
    void durationFormats() {
        assertEquals("5s", TelegramMessageBuilder.duration(5_000));
        assertEquals("2m 3s", TelegramMessageBuilder.duration(123_000));
        assertEquals("1h 1m 1s", TelegramMessageBuilder.duration(3_661_000));
    }
}
