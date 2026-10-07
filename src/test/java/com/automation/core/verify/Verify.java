package com.automation.core.verify;

import com.automation.core.config.ConfigReader;
import com.automation.core.utils.ScreenshotUtil;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Verification layer on top of TestNG's {@code Assert}: every check takes a
 * {@link FailureHandling}, so a test can choose per check whether a failure stops it, is
 * collected until the end, or is only reported as a warning.
 *
 * <pre>{@code
 * Verify.verifyEquals(page.title(), "Home", "page title", FailureHandling.CONTINUE_ON_FAILURE);
 * Verify.verifyTrue(page.isBannerShown(), "promo banner", FailureHandling.OPTIONAL);
 * Verify.verifyTrue(page.isLoggedIn(), "logged in");   // default mode: verify.failure.handling
 * Verify.check("cart total", FailureHandling.CONTINUE_ON_FAILURE,
 *     () -> Assert.assertEquals(cart.total(), 42.0, 0.001));   // any existing Assert.* call
 * }</pre>
 *
 * <p>State is per thread (parallel-safe). Failures collected by {@code CONTINUE_ON_FAILURE} are
 * turned into a test failure by {@link #applyToResult(ITestResult)}, which {@code TestListener}
 * calls after every test method - tests never need an "assert all" call. Use this from
 * {@code @Test} methods; page objects still never call assertions (see CONVENTIONS.md).
 *
 * <p>Only {@link AssertionError} is handled. Anything else thrown while computing the value you
 * pass in (e.g. a missing element) is a real error and propagates as usual.
 */
public final class Verify {

    /** Config/system property naming the mode used when a check passes no explicit one. */
    public static final String DEFAULT_HANDLING_PROPERTY = "verify.failure.handling";

    private static final Logger logger = LoggerFactory.getLogger(Verify.class);

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private Verify() {
    }

    /** One failed check: what was being verified, how it was configured, and why it failed. */
    public record Failure(String description, FailureHandling handling, AssertionError error) {

        /** Human-readable line: the description when there is one, plus the assertion's own text. */
        public String message() {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return description == null || description.isBlank() || detail.contains(description)
                ? detail
                : description + " - " + detail;
        }
    }

    /** What one test accumulated: passed-check count, collected failures, and optional warnings. */
    public record Outcome(int passed, List<Failure> failures, List<Failure> warnings) {
    }

    private static final class State {
        private final List<Failure> failures = new ArrayList<>();
        private final List<Failure> warnings = new ArrayList<>();
        private int passed;
        private Supplier<WebDriver> driver;
    }

    // ── Public checks ────────────────────────────────────────────────────────

    public static boolean verifyTrue(boolean condition, String message) {
        return verifyTrue(condition, message, null);
    }

    public static boolean verifyTrue(boolean condition, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertTrue(condition, message));
    }

    public static boolean verifyFalse(boolean condition, String message) {
        return verifyFalse(condition, message, null);
    }

    public static boolean verifyFalse(boolean condition, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertFalse(condition, message));
    }

    public static boolean verifyEquals(Object actual, Object expected, String message) {
        return verifyEquals(actual, expected, message, null);
    }

    public static boolean verifyEquals(Object actual, Object expected, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertEquals(actual, expected, message));
    }

    public static boolean verifyNotEquals(Object actual, Object unexpected, String message) {
        return verifyNotEquals(actual, unexpected, message, null);
    }

    public static boolean verifyNotEquals(Object actual, Object unexpected, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertNotEquals(actual, unexpected, message));
    }

    public static boolean verifyNull(Object actual, String message) {
        return verifyNull(actual, message, null);
    }

    public static boolean verifyNull(Object actual, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertNull(actual, message));
    }

    public static boolean verifyNotNull(Object actual, String message) {
        return verifyNotNull(actual, message, null);
    }

    public static boolean verifyNotNull(Object actual, String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.assertNotNull(actual, message));
    }

    /** Passes when {@code actual} contains {@code expectedSubstring}; a null {@code actual} fails. */
    public static boolean verifyContains(String actual, String expectedSubstring, String message) {
        return verifyContains(actual, expectedSubstring, message, null);
    }

    public static boolean verifyContains(String actual, String expectedSubstring, String message,
                                         FailureHandling handling) {
        return check(message, handling, () -> Assert.assertTrue(
            actual != null && expectedSubstring != null && actual.contains(expectedSubstring),
            message + " - expected [" + actual + "] to contain [" + expectedSubstring + "]"));
    }

    /** Records an unconditional failure, e.g. for a branch that should not be reachable. */
    public static boolean fail(String message, FailureHandling handling) {
        return check(message, handling, () -> Assert.fail(message));
    }

    /** {@link #check(String, FailureHandling, Runnable)} using the configured default mode. */
    public static boolean check(String description, Runnable assertion) {
        return check(description, null, assertion);
    }

    /**
     * Runs any assertion code - typically an existing {@code Assert.*} call - and applies the
     * failure mode to it. This is the single place all the {@code verify*} methods go through.
     *
     * @param handling mode for this check; {@code null} means the configured default
     * @return {@code true} if the assertion passed, {@code false} if it failed in a mode that
     *         lets the test continue (a failing {@code STOP_ON_FAILURE} check throws instead)
     */
    public static boolean check(String description, FailureHandling handling, Runnable assertion) {
        FailureHandling mode = handling != null ? handling : defaultHandling();
        try {
            assertion.run();
        } catch (AssertionError error) {
            return onFailure(description, mode, error);
        }
        STATE.get().passed++;
        recordStep("Verify passed: " + label(description), Status.PASSED, null, null);
        return true;
    }

    // ── Wiring used by TestListener ──────────────────────────────────────────

    /**
     * Lets a failing soft check grab a screenshot at the moment it fails. Called by
     * {@code TestListener} before each invocation; the supplier is lazy because the driver does
     * not exist yet when a {@code @BeforeMethod} starts.
     */
    public static void bindDriver(Supplier<WebDriver> driver) {
        STATE.get().driver = driver;
    }

    public static void unbindDriver() {
        STATE.get().driver = null;
    }

    /**
     * Takes (and clears) everything this thread collected so far. Always clears, so nothing can
     * leak into the next test that reuses the thread.
     */
    public static Outcome drain() {
        State state = STATE.get();
        Outcome outcome = new Outcome(state.passed, List.copyOf(state.failures), List.copyOf(state.warnings));
        state.failures.clear();
        state.warnings.clear();
        state.passed = 0;
        return outcome;
    }

    /**
     * Folds this thread's collected verifications into a finished test method's result. Call it
     * from an {@code IInvokedMethodListener.afterInvocation}, i.e. before TestNG reports the
     * result to listeners and the retry analyzer:
     * <ul>
     *   <li>test body passed but {@code CONTINUE_ON_FAILURE} failures exist - the result becomes
     *       FAILURE with a {@link VerificationError} listing all of them;</li>
     *   <li>test body already failed - the collected failures are attached to its throwable as
     *       suppressed exceptions, so nothing found earlier is lost;</li>
     *   <li>skipped results are left alone; {@code OPTIONAL} warnings never change the result.</li>
     * </ul>
     *
     * @return what was collected, so the caller can report the warnings
     */
    public static Outcome applyToResult(ITestResult result) {
        Outcome outcome = drain();
        if (outcome.failures().isEmpty()) {
            return outcome;
        }
        if (result.getStatus() == ITestResult.SUCCESS) {
            result.setStatus(ITestResult.FAILURE);
            result.setThrowable(new VerificationError(outcome.failures()));
        } else if (result.getStatus() == ITestResult.FAILURE && result.getThrowable() != null) {
            for (Failure failure : outcome.failures()) {
                result.getThrowable().addSuppressed(failure.error());
            }
        }
        return outcome;
    }

    /** Mode used when a check passes none: {@code verify.failure.handling}, else STOP_ON_FAILURE. */
    public static FailureHandling defaultHandling() {
        try {
            return FailureHandling.parse(
                ConfigReader.get(DEFAULT_HANDLING_PROPERTY, FailureHandling.STOP_ON_FAILURE.name()),
                FailureHandling.STOP_ON_FAILURE);
        } catch (RuntimeException e) {
            return FailureHandling.STOP_ON_FAILURE;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static boolean onFailure(String description, FailureHandling mode, AssertionError error) {
        Failure failure = new Failure(description, mode, error);
        State state = STATE.get();

        switch (mode) {
            case STOP_ON_FAILURE -> {
                logger.error("[Verify] FAILED (STOP_ON_FAILURE): {}", failure.message());
                recordStep("Verify failed: " + label(description), Status.FAILED, error, null);
                throw error;
            }
            case CONTINUE_ON_FAILURE -> {
                state.failures.add(failure);
                logger.error("[Verify] FAILED (CONTINUE_ON_FAILURE) - test continues: {}", failure.message());
                recordStep("Verify failed (continuing): " + label(description), Status.FAILED, error,
                    screenshot(state));
            }
            case OPTIONAL -> {
                state.warnings.add(failure);
                logger.warn("[Verify] OPTIONAL check failed - ignored: {}", failure.message());
                recordStep("Verify warning (optional): " + label(description), Status.BROKEN, error,
                    screenshot(state));
            }
            default -> throw new IllegalStateException("Unhandled mode " + mode);
        }
        return false;
    }

    private static String label(String description) {
        return description == null || description.isBlank() ? "(unnamed check)" : description;
    }

    private static byte[] screenshot(State state) {
        if (state.driver == null) {
            return new byte[0];
        }
        try {
            return ScreenshotUtil.captureScreenshotAsBytes(state.driver.get());
        } catch (RuntimeException e) {
            return new byte[0];
        }
    }

    /**
     * Adds the check to the open Allure test case as its own step, so the report shows what was
     * verified and which checks failed without the test having stopped. A no-op when no case is
     * open (plain unit tests, Allure absent); never throws, a report problem must not change a
     * test's outcome.
     */
    private static void recordStep(String name, Status status, Throwable error, byte[] screenshot) {
        try {
            AllureLifecycle lifecycle = Allure.getLifecycle();
            if (!lifecycle.getCurrentTestCaseOrStep().isPresent()) {
                return;
            }
            String uuid = UUID.randomUUID().toString();
            lifecycle.startStep(uuid, new StepResult().setName(name).setStatus(status));
            try {
                if (error != null) {
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    lifecycle.updateStep(uuid, step -> step.setStatusDetails(
                        new StatusDetails().setMessage(error.getMessage()).setTrace(trace.toString())));
                }
                if (screenshot != null && screenshot.length > 0) {
                    Allure.addAttachment("Screenshot - " + name, "image/png",
                        new ByteArrayInputStream(screenshot), "png");
                }
            } finally {
                lifecycle.stopStep(uuid);
            }
        } catch (RuntimeException e) {
            logger.debug("[Verify] could not record Allure step: {}", e.getMessage());
        }
    }
}
