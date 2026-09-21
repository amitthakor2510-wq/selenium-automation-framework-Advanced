package com.automation.sites.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.automation.core.exceptions.DataFileException;
import com.automation.core.exceptions.KeywordExecutionException;
import org.openqa.selenium.WebDriverException;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(RetryAnalyzer.class);

    private int count = 0;

    @Override
    public boolean retry(ITestResult result) {
        if (isDeterministicFailure(result.getThrowable())) {
            logger.info("Not retrying test [" + result.getName() + "] — "
                + "failure is a deterministic authoring problem (bad config, bad keyword step, "
                + "or bad test-data file), not a flaky condition. Retrying would fail identically.");
            return false;
        }

        int maxRetry = ConfigReader.getInt("retry.count", 2);
        if (count < maxRetry) {
            count++;
            logger.info("Retrying test [" + result.getName() + "] attempt " + count + " of " + maxRetry);
            return true;
        }
        return false;
    }

    /**
     * ConfigException (missing/invalid config), DataFileException
     * (unreadable/malformed test-data file) and KeywordExecutionException
     * (bad keyword step, unknown locator key, malformed step row) are all the
     * same on attempt 1 and attempt 5 — retrying just burns retry.count
     * for no benefit and delays the real fix being noticed.
     *
     * BUG FIX: KeywordEngine.run() wraps EVERY exception a step throws —
     * including a Selenium TimeoutException, StaleElementReferenceException
     * or a crashed browser session — in a KeywordExecutionException so the
     * failing step is named in the message. The old check treated any
     * KeywordExecutionException anywhere in the cause chain as deterministic,
     * so a genuinely flaky failure inside a keyword-driven test (every
     * SAHMAT/keyword-login scenario) was never retried at all. A
     * KeywordExecutionException now only counts as deterministic when no
     * Selenium WebDriverException sits underneath it — i.e. when it was
     * thrown by the framework itself (unknown keyword/locator key/malformed
     * row), not merely relayed from the browser. ConfigException and
     * DataFileException stay unconditional: nothing a browser does can make
     * them succeed on retry.
     *
     * DriverInitializationException is deliberately NOT included here:
     * some causes (a Grid node being briefly unreachable, a transient
     * chromedriver download hiccup) genuinely can succeed on retry.
     *
     * Walks the cause chain, not just the top-level throwable, since
     * TestNG/Selenium often wrap the original exception (e.g. inside an
     * ExceptionInInitializerError from a static initializer). Package-private
     * and static so RetryAnalyzerTest can exercise it without a TestNG
     * ITestResult or ConfigReader state.
     */
    static boolean isDeterministicFailure(Throwable t) {
        boolean sawKeywordFailure = false;
        boolean sawBrowserFailure = false;
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConfigException || cause instanceof DataFileException) {
                return true;
            }
            if (cause instanceof KeywordExecutionException) {
                sawKeywordFailure = true;
            }
            if (cause instanceof WebDriverException) {
                sawBrowserFailure = true;
            }
        }
        return sawKeywordFailure && !sawBrowserFailure;
    }

    /** Number of retry attempts already made for this test method. TestNG keeps a single
     *  IRetryAnalyzer instance per method across all its attempts, so this reflects the
     *  true attempt count when read from TestListener after the method finishes — used to
     *  tag a test that failed at least once before eventually passing as "flaky" in Allure. */
    public int getCount() {
        return count;
    }
}
