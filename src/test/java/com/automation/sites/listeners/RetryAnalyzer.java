package com.automation.sites.listeners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.ConfigException;
import com.automation.core.exceptions.DataFileException;
import com.automation.core.exceptions.KeywordExecutionException;
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
     * ConfigException (missing/invalid config), KeywordExecutionException
     * (bad keyword step, unknown locator key, malformed step row), and
     * DataFileException (unreadable/malformed test-data file) are all the
     * same on attempt 1 and attempt 5 — retrying just burns retry.count
     * for no benefit and delays the real fix being noticed.
     *
     * DriverInitializationException is deliberately NOT included here:
     * unlike those three, some causes (a Grid node being briefly
     * unreachable, a transient chromedriver download hiccup) genuinely can
     * succeed on retry, so those still go through the normal retry.count
     * path below rather than being auto-excluded.
     *
     * Walks the cause chain, not just the top-level throwable, since
     * TestNG/Selenium often wrap the original exception (e.g. inside an
     * ExceptionInInitializerError from a static initializer).
     */
    private boolean isDeterministicFailure(Throwable t) {
        while (t != null) {
            if (t instanceof ConfigException
                || t instanceof KeywordExecutionException
                || t instanceof DataFileException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** Number of retry attempts already made for this test method. TestNG keeps a single
     *  IRetryAnalyzer instance per method across all its attempts, so this reflects the
     *  true attempt count when read from TestListener after the method finishes — used to
     *  tag a test that failed at least once before eventually passing as "flaky" in Allure. */
    public int getCount() {
        return count;
    }
}
