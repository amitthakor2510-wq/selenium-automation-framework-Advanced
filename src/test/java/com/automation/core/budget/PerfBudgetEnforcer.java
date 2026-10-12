package com.automation.core.budget;

import com.automation.core.budget.PerfSamples.Sample;
import com.automation.core.config.ConfigReader;
import com.automation.core.verify.FailureHandling;
import com.automation.core.verify.Verify;
import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.ITestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the timings a test collected ({@link PerfSamples}) into pass/fail: any page load or API
 * call slower than its {@link PerfBudget} limit is reported through {@link Verify}, so a slow
 * response fails the run exactly like a failed assertion does - and shows up in the same Allure
 * steps, Extent report and CI result.
 *
 * <p>{@code TestListener} calls {@link #enforce(ITestResult)} once after each test method,
 * BEFORE {@code Verify.applyToResult}, so the violations are folded into the result by the same
 * code path that handles every other soft failure. Because the test body has already finished by
 * then, {@code STOP_ON_FAILURE} cannot stop anything and behaves like
 * {@code CONTINUE_ON_FAILURE}.
 *
 * <pre>
 * perf.budget.enabled=true                    # master switch (false = measure nothing, fail nothing)
 * perf.budget.handling=CONTINUE_ON_FAILURE    # or OPTIONAL = report slow calls as warnings only
 * </pre>
 *
 * Limits themselves are described on {@link PerfBudget}. Samples recorded during setup
 * ({@code @BeforeClass}/{@code @BeforeMethod}) count toward the first test that follows; samples
 * recorded in {@code @After*} methods are discarded (see TestListener).
 */
public final class PerfBudgetEnforcer {

    private static final Logger logger = LoggerFactory.getLogger(PerfBudgetEnforcer.class);

    /** Set when the current test broke a budget; read by RetryAnalyzer (same thread). */
    private static final ThreadLocal<Boolean> EXCEEDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Config warnings repeat for every test otherwise - log each once per JVM. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** One timing together with the limit it was held to ({@code limitMs == 0} means no limit). */
    record Evaluation(Sample sample, long limitMs) {
        boolean exceeded() {
            return limitMs > 0 && sample.millis() > limitMs;
        }
    }

    private PerfBudgetEnforcer() {
    }

    public static boolean isEnabled() {
        return ConfigReader.getBoolean("perf.budget.enabled", true);
    }

    /**
     * Evaluates and clears this thread's samples; never throws, a budget problem must not hide the
     * real result. {@code result} is only used for log messages and may be null (the BDD hook
     * calls this from inside the scenario, where no TestNG result is at hand).
     */
    public static void enforce(ITestResult result) {
        List<Sample> samples = PerfSamples.drain();
        if (samples.isEmpty()) {
            return;
        }
        try {
            if (!isEnabled()) {
                return;
            }
            PerfBudget budget = PerfBudget.fromConfig();
            for (String warning : budget.warnings()) {
                if (WARNED.add(warning)) {
                    logger.warn("[PerfBudget] {}", warning);
                }
            }
            FailureHandling mode = FailureHandling.parse(
                ConfigReader.get("perf.budget.handling", FailureHandling.CONTINUE_ON_FAILURE.name()),
                FailureHandling.CONTINUE_ON_FAILURE);
            apply(result == null ? null : result.getName(), samples, budget, mode);
        } catch (RuntimeException e) {
            logger.warn("[PerfBudget] Could not evaluate budgets for [{}]: {}",
                result == null ? "(scenario)" : result.getName(), e.getMessage());
        }
    }

    /**
     * Compares the samples with the budget, attaches the report and reports every violation
     * through {@link Verify}. Split from {@link #enforce} so it can be tested without any
     * configuration. {@code STOP_ON_FAILURE} behaves like {@code CONTINUE_ON_FAILURE}: the test
     * body is already over, so there is nothing left to stop.
     *
     * @return how many measurements were over their limit
     */
    static int apply(List<Sample> samples, PerfBudget budget, FailureHandling mode) {
        return apply(null, samples, budget, mode);
    }

    /** Same, also remembering which test the timings belong to for the run-level {@link PerfBudgetReport}. */
    static int apply(String testName, List<Sample> samples, PerfBudget budget, FailureHandling mode) {
        FailureHandling effective = mode == FailureHandling.STOP_ON_FAILURE
            ? FailureHandling.CONTINUE_ON_FAILURE : mode;
        List<Evaluation> evaluations = evaluate(samples, budget);
        attachReport(evaluations);
        for (Evaluation e : evaluations) {
            PerfBudgetReport.record(testName, e.sample(), e.limitMs());
        }
        int exceeded = 0;
        for (Evaluation e : evaluations) {
            if (e.exceeded()) {
                exceeded++;
                Verify.check("Performance budget - " + describe(e.sample()), effective,
                    () -> Assert.fail(message(e)));
            }
        }
        // Only a real failure blocks the retry. In OPTIONAL mode a slow call is just a warning, so
        // the test is not failing because of it and a retry would be unrelated to the budget.
        if (exceeded > 0 && effective != FailureHandling.OPTIONAL) {
            EXCEEDED.set(Boolean.TRUE);
        }
        return exceeded;
    }

    /** Forgets a previous test's verdict; called before each test method starts. */
    public static void resetExceeded() {
        EXCEEDED.set(Boolean.FALSE);
    }

    /**
     * True when this thread's current test exceeded a budget and retrying it should be skipped.
     * A retry would usually just be faster the second time and turn a real slowness finding into
     * a green "flaky" pass, defeating the point of the budget. Set {@code perf.budget.retry=true}
     * to allow retries of budget failures anyway.
     */
    public static boolean shouldSkipRetry() {
        if (!EXCEEDED.get()) {
            return false;
        }
        boolean retryAllowed;
        try {
            retryAllowed = ConfigReader.getBoolean("perf.budget.retry", false);
        } catch (RuntimeException e) {
            retryAllowed = false;
        }
        return shouldSkipRetry(retryAllowed);
    }

    static boolean shouldSkipRetry(boolean retryAllowed) {
        return EXCEEDED.get() && !retryAllowed;
    }

    static List<Evaluation> evaluate(List<Sample> samples, PerfBudget budget) {
        List<Evaluation> out = new ArrayList<>();
        for (Sample sample : samples) {
            out.add(new Evaluation(sample, budget.limitFor(sample.kind(), sample.name())));
        }
        return out;
    }

    static String message(Evaluation e) {
        return describe(e.sample()) + " took " + e.sample().millis()
            + " ms, budget is " + e.limitMs() + " ms";
    }

    private static String describe(Sample sample) {
        switch (sample.kind()) {
            case API:
                return "API " + sample.name();
            case SCREEN:
                return "screen " + sample.name();
            default:
                return "page load " + sample.name();
        }
    }

    static String report(List<Evaluation> evaluations) {
        StringBuilder text = new StringBuilder();
        for (Evaluation e : evaluations) {
            String status = e.limitMs() == 0 ? "NO LIMIT" : e.exceeded() ? "SLOW    " : "OK      ";
            text.append(status).append("  ").append(describe(e.sample()))
                .append("  ").append(e.sample().millis()).append(" ms");
            if (e.limitMs() > 0) {
                text.append(" (budget ").append(e.limitMs()).append(" ms)");
            }
            text.append(System.lineSeparator());
        }
        return text.toString();
    }

    /** Attached for passing tests too: the numbers are worth seeing even when comfortably under budget. */
    private static void attachReport(List<Evaluation> evaluations) {
        try {
            if (Allure.getLifecycle().getCurrentTestCaseOrStep().isPresent()) {
                Allure.addAttachment("Performance budget", "text/plain", report(evaluations));
            }
        } catch (RuntimeException e) {
            logger.debug("[PerfBudget] could not attach report: {}", e.getMessage());
        }
    }
}
