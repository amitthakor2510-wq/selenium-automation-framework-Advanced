package com.automation.core.budget;

import com.automation.core.budget.PerfBudgetReport.Entry;
import com.automation.core.budget.PerfSamples.Kind;
import com.automation.core.budget.PerfSamples.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the run-level report: percentiles, suggested limits and the text layout. */
class PerfBudgetReportTest {

    private static Entry api(String test, String name, long millis, long limit) {
        return new Entry(test, new Sample(Kind.API, name, millis), limit);
    }

    @Test
    void percentileUsesNearestRank() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertEquals(50, PerfBudgetReport.percentile(values, 50));
        assertEquals(100, PerfBudgetReport.percentile(values, 95));
        assertEquals(100, PerfBudgetReport.percentile(values, 100));
        assertEquals(42, PerfBudgetReport.percentile(List.of(42L), 95));
    }

    @Test
    void suggestedLimitIsP95TimesOnePointFiveRoundedUpToTheNextHundred() {
        // p95 of 1..20 x 100 ms is 1900 ms; x 1.5 = 2850 -> 2900
        List<Long> values = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            values.add(i * 100);
        }
        assertEquals(2900, PerfBudgetReport.suggestedLimit(values));
        assertEquals(100, PerfBudgetReport.suggestedLimit(List.of(10L, 10L, 10L)), "never below one step");
    }

    @Test
    void reportListsViolationsSlowestAndPerEndpointStats() {
        List<Entry> entries = List.of(
            api("A#t1", "GET /books", 400, 2000),
            api("A#t2", "GET /books", 500, 2000),
            api("A#t3", "GET /books", 3000, 2000),
            api("B#t4", "POST /login", 100, 0));
        String report = PerfBudgetReport.build(entries);

        assertTrue(report.startsWith("Performance budget summary: 4 measurements, 1 over budget"), report);
        assertTrue(report.contains("API GET /books  3000 ms (budget 2000 ms)  in A#t3"), report);
        assertTrue(report.contains("calls=3"), "three calls of /books are counted together");
        assertTrue(report.contains("suggested=4500"), "p95 3000 x 1.5 = 4500 for /books");
        assertTrue(report.contains("limit=none"), "limit 0 is shown as none");
        assertTrue(report.contains("suggested=n/a"), "one call is too few to suggest a limit");
    }

    @Test
    void reportWithNothingOverBudgetSaysSo() {
        String report = PerfBudgetReport.build(List.of(api("A#t", "GET /ok", 10, 1000)));
        assertTrue(report.contains("0 over budget"), report);
        assertTrue(report.contains("  none"), report);
        assertFalse(report.contains("(budget"), "no violation lines");
    }

    @Test
    void recordedEntriesAreCappedAndResettable() {
        PerfBudgetReport.reset();
        PerfBudgetReport.record(null, new Sample(Kind.PAGE_LOAD, "/x", 5), 100);
        PerfBudgetReport.reset();
        // A reset report writes nothing and must not throw.
        PerfBudgetReport.writeSummary();
    }
}
