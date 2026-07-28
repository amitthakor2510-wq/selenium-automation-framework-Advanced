package com.automation.sites.listeners;

import java.util.logging.Logger;

import com.automation.core.config.ConfigReader;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger logger = Logger.getLogger(RetryAnalyzer.class.getName());

    private int count = 0;

    @Override
    public boolean retry(ITestResult result) {
        int maxRetry = ConfigReader.getInt("retry.count", 2);
        if (count < maxRetry) {
            count++;
            logger.info("Retrying test [" + result.getName() + "] attempt " + count + " of " + maxRetry);
            return true;
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
