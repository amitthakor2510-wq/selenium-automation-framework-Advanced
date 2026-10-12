package com.automation.core.budget;

import com.automation.core.budget.PerfSamples.Kind;
import com.automation.core.budget.PerfSamples.Sample;
import com.automation.core.verify.FailureHandling;
import com.automation.core.verify.Verify;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebDriver-free tests for {@link PerfBudget}, {@link PerfSamples} and the evaluation in {@link PerfBudgetEnforcer}. */
class PerfBudgetTest {

    @AfterEach
    void clean() {
        PerfSamples.clear();
        PerfBudgetEnforcer.resetExceeded();
        Verify.drain();
    }

    @Test
    void defaultsApplyWhenThereAreNoOverrides() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "", "");
        assertEquals(5000, budget.limitFor(Kind.API, "GET /users"));
        assertEquals(15000, budget.limitFor(Kind.PAGE_LOAD, "/books"));
    }

    @Test
    void apiOverrideMatchesMethodAndWildcardPath() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "GET /BookStore/v1/Books=2000; POST /Account/*=8000", "");
        assertEquals(2000, budget.limitFor(Kind.API, "GET /BookStore/v1/Books"));
        assertEquals(5000, budget.limitFor(Kind.API, "POST /BookStore/v1/Books"), "method must match");
        assertEquals(8000, budget.limitFor(Kind.API, "POST /Account/v1/GenerateToken"));
    }

    @Test
    void apiOverrideWithoutMethodMatchesAnyMethod() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "/slow/*=9000; * /any=100", "");
        assertEquals(9000, budget.limitFor(Kind.API, "DELETE /slow/thing"));
        assertEquals(100, budget.limitFor(Kind.API, "PUT /any"));
    }

    @Test
    void firstMatchingOverrideWins() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "GET /a/b=100; GET /a/*=900", "");
        assertEquals(100, budget.limitFor(Kind.API, "GET /a/b"));
        assertEquals(900, budget.limitFor(Kind.API, "GET /a/c"));
    }

    @Test
    void pageLoadOverrideMatchesPath() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "", "/books=20000; /upload-download=25000");
        assertEquals(20000, budget.limitFor(Kind.PAGE_LOAD, "/books"));
        assertEquals(15000, budget.limitFor(Kind.PAGE_LOAD, "/books/extra"), "no wildcard, so exact path only");
    }

    @Test
    void screenBudgetHasItsOwnDefaultAndOverrides() {
        PerfBudget budget = PerfBudget.of(5000, 15000, 10000, "", "", "app launch=60000; element*Network*=20000");
        assertEquals(60000, budget.limitFor(Kind.SCREEN, "app launch"));
        assertEquals(20000, budget.limitFor(Kind.SCREEN, "element By.xpath: //Network & internet"));
        assertEquals(10000, budget.limitFor(Kind.SCREEN, "element By.id: other"));
        assertEquals(15000, budget.limitFor(Kind.PAGE_LOAD, "/app launch"), "screen rules never apply to page loads");
        assertEquals(10000, PerfBudget.of(5000, 15000, "", "").limitFor(Kind.SCREEN, "x"), "default without config");
    }

    @Test
    void screenSamplesAreRecordedAndEvaluatedLikeOthers() {
        PerfSamples.recordScreen("app launch", 70000);
        PerfSamples.recordScreen("", 1);
        List<Sample> samples = PerfSamples.drain();
        assertEquals("app launch", samples.get(0).name());
        assertEquals("screen", samples.get(1).name(), "a blank label gets a generic one");
        PerfBudget budget = PerfBudget.of(5000, 15000, 60000, "", "", "");
        List<PerfBudgetEnforcer.Evaluation> evaluations = PerfBudgetEnforcer.evaluate(samples, budget);
        assertTrue(evaluations.get(0).exceeded());
        assertEquals("screen app launch took 70000 ms, budget is 60000 ms", PerfBudgetEnforcer.message(evaluations.get(0)));
    }

    @Test
    void zeroMeansNoLimit() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "GET /export=0", "");
        assertEquals(0, budget.limitFor(Kind.API, "GET /export"));
        PerfBudget noDefault = PerfBudget.of(0, 0, "", "");
        assertEquals(0, noDefault.limitFor(Kind.API, "GET /x"));
    }

    @Test
    void malformedOverridesAreIgnoredWithAWarning() {
        PerfBudget budget = PerfBudget.of(5000, 15000, "GET /a; GET /b=abc; GET /ok=700", "");
        assertEquals(2, budget.warnings().size());
        assertEquals(700, budget.limitFor(Kind.API, "GET /ok"));
        assertEquals(5000, budget.limitFor(Kind.API, "GET /a"));
    }

    @Test
    void pathsAreNormalisedForPageLoads() {
        PerfSamples.recordPageLoad("https://demoqa.com/books?x=1", 10);
        PerfSamples.recordPageLoad("webtables", 20);
        PerfSamples.recordPageLoad("", 30);
        List<Sample> samples = PerfSamples.drain();
        assertEquals("/books", samples.get(0).name());
        assertEquals("/webtables", samples.get(1).name());
        assertEquals("/", samples.get(2).name());
        assertTrue(PerfSamples.drain().isEmpty(), "drain must clear");
    }

    @Test
    void samplesPerThreadAreCappedSoNothingGrowsUnbounded() {
        for (int i = 0; i < PerfSamples.MAX_SAMPLES_PER_THREAD + 100; i++) {
            PerfSamples.record(Kind.API, "GET /x", 1);
        }
        assertEquals(PerfSamples.MAX_SAMPLES_PER_THREAD, PerfSamples.drain().size());
    }

    @Test
    void exceedingABudgetFailsTheTestAndBlocksRetryUnlessAllowed() {
        PerfBudget budget = PerfBudget.of(1000, 2000, "", "");
        List<Sample> samples = List.of(
            new Sample(Kind.API, "GET /fast", 10),
            new Sample(Kind.API, "GET /slow", 5000));

        assertEquals(1, PerfBudgetEnforcer.apply(samples, budget, FailureHandling.CONTINUE_ON_FAILURE));
        assertTrue(PerfBudgetEnforcer.shouldSkipRetry(false), "a budget failure must not be retried by default");
        assertFalse(PerfBudgetEnforcer.shouldSkipRetry(true), "perf.budget.retry=true allows the retry");
        assertEquals(1, Verify.drain().failures().size(), "the slow call is a verification failure");

        PerfBudgetEnforcer.resetExceeded();
        assertFalse(PerfBudgetEnforcer.shouldSkipRetry(false), "verdict is cleared for the next test");
    }

    @Test
    void optionalModeReportsButNeitherFailsNorBlocksRetry() {
        PerfBudget budget = PerfBudget.of(1000, 2000, "", "");
        assertEquals(1, PerfBudgetEnforcer.apply(List.of(new Sample(Kind.PAGE_LOAD, "/slow", 9000)), budget,
            FailureHandling.OPTIONAL));
        assertFalse(PerfBudgetEnforcer.shouldSkipRetry(false), "a warning is not a failure, retry stays possible");
        Verify.Outcome outcome = Verify.drain();
        assertEquals(0, outcome.failures().size());
        assertEquals(1, outcome.warnings().size());
    }

    @Test
    void stopOnFailureBehavesLikeContinueAfterTheTestBodyIsOver() {
        PerfBudget budget = PerfBudget.of(1000, 2000, "", "");
        assertEquals(1, PerfBudgetEnforcer.apply(List.of(new Sample(Kind.API, "GET /slow", 5000)), budget,
            FailureHandling.STOP_ON_FAILURE));
        assertEquals(1, Verify.drain().failures().size());
    }

    @Test
    void nothingOverBudgetLeavesNoVerdict() {
        PerfBudget budget = PerfBudget.of(1000, 2000, "", "");
        assertEquals(0, PerfBudgetEnforcer.apply(List.of(new Sample(Kind.API, "GET /ok", 10)), budget,
            FailureHandling.CONTINUE_ON_FAILURE));
        assertFalse(PerfBudgetEnforcer.shouldSkipRetry(false));
        assertEquals(0, Verify.drain().failures().size());
    }

    @Test
    void onlySamplesOverTheirLimitAreExceeded() {
        PerfBudget budget = PerfBudget.of(1000, 2000, "GET /free=0", "");
        List<PerfBudgetEnforcer.Evaluation> evaluations = PerfBudgetEnforcer.evaluate(List.of(
            new Sample(Kind.API, "GET /fast", 999),
            new Sample(Kind.API, "GET /slow", 1001),
            new Sample(Kind.API, "GET /free", 60000),
            new Sample(Kind.PAGE_LOAD, "/home", 2001)), budget);

        assertFalse(evaluations.get(0).exceeded());
        assertTrue(evaluations.get(1).exceeded());
        assertFalse(evaluations.get(2).exceeded(), "limit 0 = exempt");
        assertTrue(evaluations.get(3).exceeded());
        assertEquals("API GET /slow took 1001 ms, budget is 1000 ms", PerfBudgetEnforcer.message(evaluations.get(1)));
        assertTrue(PerfBudgetEnforcer.report(evaluations).contains("SLOW"));
    }
}
